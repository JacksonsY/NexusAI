package com.nexusai.billing.event;

import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.contract.usage.UsageEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBatch;
import org.redisson.api.RStream;
import org.redisson.api.RStreamAsync;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 用量事件 push 到 Redis Stream（网关 Producer；业务面计价扣钱·nai_usage_log 作 outbox）。
 * <p><b>高并发计量管线（{@code nexusai.usage.batch.enabled}）</b>：默认关=每事件直接 XADD（低延迟·强可靠）；
 * 开启=内存队列缓冲 + 守护线程批量 {@code RBatch} pipeline flush（达 batch-size 或每 flush-interval·削峰填谷减往返）。
 * 两模式都有 nai_usage_log outbox 兜底；批量模式进程崩溃时未 flush 的内存事件由 outbox 重投（不丢计费）。
 *
 * @author nexusai
 */
@Slf4j
@Service
public class UsageEventPublisher {

    private final RedissonClient redisson;
    private final boolean batchEnabled;
    private final int batchSize;
    private final long flushIntervalMs;

    private final ConcurrentLinkedQueue<UsageEvent> buffer = new ConcurrentLinkedQueue<>();
    private volatile boolean running;
    private Thread flushThread;

    public UsageEventPublisher(RedissonClient redisson,
                               @Value("${nexusai.usage.batch.enabled:false}") boolean batchEnabled,
                               @Value("${nexusai.usage.batch.size:200}") int batchSize,
                               @Value("${nexusai.usage.batch.flush-interval-ms:500}") long flushIntervalMs) {
        this.redisson = redisson;
        this.batchEnabled = batchEnabled;
        this.batchSize = Math.max(1, batchSize);
        this.flushIntervalMs = Math.max(50, flushIntervalMs);
    }

    public void publish(UsageEvent event) {
        if (batchEnabled) {
            buffer.offer(event);
            if (buffer.size() >= batchSize) {
                flush();   // 达批量阈值即时 flush（与守护线程定时 flush 互补）
            }
            return;
        }
        directAdd(event);
    }

    private void directAdd(UsageEvent event) {
        try {
            RStream<String, String> stream = redisson.getStream(GatewayConstants.USAGE_STREAM_KEY, StringCodec.INSTANCE);
            stream.add(StreamAddArgs.entries(event.toMap()));   // record → Stream entries（String KV·wire 单一真理源）
        } catch (Exception e) {
            // 投递失败不阻断主链——nai_usage_log outbox 兜底重投（保证可靠投递）
            log.error("UsageEvent 投递 Redis Stream 失败，待 outbox 重投: {}", event.requestId(), e);
        }
    }

    /** 批量 flush（RBatch pipeline 一次往返多 XADD）。 */
    private synchronized void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        List<UsageEvent> drained = new ArrayList<>(batchSize);
        UsageEvent e;
        while (drained.size() < batchSize && (e = buffer.poll()) != null) {
            drained.add(e);
        }
        if (drained.isEmpty()) {
            return;
        }
        try {
            RBatch batch = redisson.createBatch();
            for (UsageEvent ev : drained) {
                RStreamAsync<String, String> s =
                        batch.getStream(GatewayConstants.USAGE_STREAM_KEY, StringCodec.INSTANCE);
                s.addAsync(StreamAddArgs.entries(ev.toMap()));
            }
            batch.execute();
        } catch (Exception ex) {
            log.error("UsageEvent 批量投递失败（{} 条待 outbox 重投）: {}", drained.size(), ex.toString());
        }
    }

    @PostConstruct
    public void start() {
        if (!batchEnabled) {
            return;
        }
        running = true;
        flushThread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(flushIntervalMs);
                    flush();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ex) {
                    log.warn("UsageEvent 定时 flush 异常: {}", ex.toString());
                }
            }
        }, "nai-usage-flush");
        flushThread.setDaemon(true);
        flushThread.start();
        log.info("用量事件高并发管线启动（批量·size={}·interval={}ms）", batchSize, flushIntervalMs);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (flushThread != null) {
            flushThread.interrupt();
        }
        flush();   // 关停前 flush 残余（剩余仍有 outbox 兜底）
    }
}
