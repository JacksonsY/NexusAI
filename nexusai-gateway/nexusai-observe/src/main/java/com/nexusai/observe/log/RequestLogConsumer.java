package com.nexusai.observe.log;

import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.persistence.log.RequestLogRecord;
import com.nexusai.persistence.log.RequestLogStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.AutoClaimResult;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 请求日志消费者：消费组阻塞批量 drain Redis Stream → 落 nai_request_log（日志管线 MySQL 阶段）。
 * <p>独立守护线程消费（不占 Netty/调度线程）；消费组保证多节点分摊与 at-least-once；ack 后由 MySQL 落地。
 * 真批量 insert 优化（rewriteBatchedStatements）留后续阶段。
 *
 * @author nexusai
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequestLogConsumer {

    private static final int BATCH = 200;
    private static final Duration BLOCK = Duration.ofSeconds(2);
    /** PEL 重认领 idle 阈值（ms）：超此未 ack 判定持有者卡死/已死可重认领·须显著大于单批落库耗时防误抢在途消息。 */
    private static final long RECLAIM_IDLE_MS = 60_000L;

    private final RedissonClient redisson;
    /** 日志域存储 SPI（按 nexusai.log.store 三选一注入 mysql/pg/clickhouse driver·observe 不耦合具体存储）。 */
    private final RequestLogStore requestLogStore;

    /** 稳定消费者名（主机名优先·跨重启稳定以复用本实例 PEL，避免每次重启换名致旧 PEL 永久成孤儿丢失）。 */
    private final String consumer = resolveConsumerName();
    private volatile boolean running = true;
    private Thread worker;

    @PostConstruct
    public void start() {
        RStream<String, String> stream = redisson.getStream(
                GatewayConstants.REQUEST_LOG_STREAM_KEY, StringCodec.INSTANCE);
        try {
            stream.createGroup(StreamCreateGroupArgs.name(GatewayConstants.REQUEST_LOG_GROUP).makeStream());
            log.info("请求日志消费组创建 group={}", GatewayConstants.REQUEST_LOG_GROUP);
        } catch (Exception e) {
            log.debug("请求日志消费组已存在 group={}", GatewayConstants.REQUEST_LOG_GROUP);
        }
        worker = new Thread(() -> loop(stream), "requestlog-consumer");
        worker.setDaemon(true);
        worker.start();
        log.info("请求日志消费者启动 consumer={}", consumer);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void loop(RStream<String, String> stream) {
        while (running) {
            try {
                // 先重认领滞留 PEL（本实例重启前的旧 PEL + 已死消费者的 PEL·idle 超阈值）再读新消息：
                // 否则落库失败留 PEL 的条目永无重投（旧实现每次重启换随机 consumer 名→旧 PEL 永久成孤儿丢失）。
                reclaimStuck(stream);
                Map<StreamMessageId, Map<String, String>> msgs = stream.readGroup(
                        GatewayConstants.REQUEST_LOG_GROUP, consumer,
                        StreamReadGroupArgs.neverDelivered().count(BATCH).timeout(BLOCK));
                persist(stream, msgs);
            } catch (Exception e) {
                if (!running) {
                    break;
                }
                log.error("请求日志消费循环异常，稍后重试", e);
                sleep();
            }
        }
        log.info("请求日志消费者停止 consumer={}", consumer);
    }

    /** 重认领 idle 超阈值的 PEL 条目（本实例旧 PEL + 死消费者 PEL）→ 落库 ack，防孤儿丢失。 */
    private void reclaimStuck(RStream<String, String> stream) {
        try {
            AutoClaimResult<String, String> res = stream.autoClaim(GatewayConstants.REQUEST_LOG_GROUP,
                    consumer, RECLAIM_IDLE_MS, TimeUnit.MILLISECONDS, StreamMessageId.MIN, BATCH);
            Map<StreamMessageId, Map<String, String>> reclaimed = res == null ? null : res.getMessages();
            if (reclaimed != null && !reclaimed.isEmpty()) {
                persist(stream, reclaimed);
                log.info("重认领滞留请求日志 {} 条（PEL idle>{}ms）", reclaimed.size(), RECLAIM_IDLE_MS);
            }
        } catch (Exception e) {
            log.debug("autoClaim 重认领失败（不影响新消息消费）: {}", e.toString());
        }
    }

    /** 落库 + ack 一批（save 失败不 ack·留 PEL 待下轮 reclaim 重投）。 */
    private void persist(RStream<String, String> stream, Map<StreamMessageId, Map<String, String>> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return;
        }
        for (Map.Entry<StreamMessageId, Map<String, String>> e : msgs.entrySet()) {
            try {
                requestLogStore.save(toRecord(e.getValue()));
                stream.ack(GatewayConstants.REQUEST_LOG_GROUP, e.getKey());
            } catch (Exception ex) {
                log.error("请求日志落库失败 id={}", e.getKey(), ex);
            }
        }
    }

    /** 稳定消费者名（主机名优先·跨重启稳定以复用 PEL；取不到则退随机名·reclaim 经 autoClaim 仍兜底回收孤儿）。 */
    private static String resolveConsumerName() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            if (host != null && !host.isBlank()) {
                return "consumer-" + host.trim();
            }
        } catch (Exception ignore) {
            // 主机名解析失败：退随机名
        }
        return "consumer-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static RequestLogRecord toRecord(Map<String, String> m) {
        RequestLogRecord r = new RequestLogRecord();
        r.setRequestId(m.get("requestId"));
        r.setTraceId(emptyToNull(m.get("traceId")));
        r.setProjectCode(emptyToNull(m.get("projectCode")));
        r.setWorkspaceId(parseLong(m.get("workspaceId")));
        r.setChannelId(parseLong(m.get("channelId")));
        r.setKeyId(parseLong(m.get("keyId")));
        r.setModel(emptyToNull(m.get("model")));
        r.setTargetModel(emptyToNull(m.get("targetModel")));
        r.setModality(emptyToNull(m.get("modality")));
        r.setStreamMode("true".equals(m.get("streamMode")));
        r.setApiFormat(emptyToNull(m.get("apiFormat")));
        r.setEndpointApiFormat(emptyToNull(m.get("endpointApiFormat")));
        r.setPromptTokens(parseInt(m.get("promptTokens")));
        r.setCompletionTokens(parseInt(m.get("completionTokens")));
        r.setCacheTokens(parseInt(m.get("cacheTokens")));
        r.setCacheCreationTokens(parseInt(m.get("cacheCreationTokens")));
        r.setTotalTokens(parseInt(m.get("totalTokens")));
        r.setCostUsd(parseBig(m.get("costUsd")));
        r.setStatus(parseInt(m.get("status")));
        r.setHttpStatus(parseInt(m.get("httpStatus")));
        r.setErrorCode(emptyToNull(m.get("errorCode")));
        r.setRetryCount(parseInt(m.get("retryCount")));
        r.setFallbackCount(parseInt(m.get("fallbackCount")));
        r.setUsageSource(emptyToNull(m.get("usageSource")));
        r.setTtftMs(parseLong(m.get("ttftMs")));
        r.setDurationMs(parseLong(m.get("durationMs")));
        r.setClientIp(emptyToNull(m.get("clientIp")));
        r.setUserAgent(emptyToNull(m.get("userAgent")));
        r.setClientFamily(emptyToNull(m.get("clientFamily")));
        r.setPromptHash(emptyToNull(m.get("promptHash")));
        // P14：显式兜底 create_dt（独立日志库手建工厂无 MetaObjectHandler·主库路径同值无害）→ 防 create_dt 落 null
        r.setCreateDt(LocalDateTime.now());
        return r;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static Integer parseInt(String s) {
        try {
            return (s == null || s.isEmpty()) ? null : Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLong(String s) {
        try {
            return (s == null || s.isEmpty()) ? null : Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal parseBig(String s) {
        try {
            return (s == null || s.isEmpty()) ? null : new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void sleep() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
