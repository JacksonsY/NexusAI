package com.nexusai.observe.log;

import com.nexusai.common.constant.GatewayConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 请求日志生产者：把一条请求日志 push 到 Redis Stream（缓冲/解耦热路径）。
 * 由 {@link RequestLogConsumer} 异步批量落 nai_request_log。投递失败不阻断主链（日志非强一致）。
 *
 * @author nexusai
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequestLogPublisher {

    /**
     * Stream 近似裁剪上限（XADD MAXLEN ~ N）：消费者 ack 只清 PEL 不删 stream 条目，无裁剪则这张「最大表」对应的
     * Stream 会无界增长顶满 Redis 内存——既可能驱逐 noeviction 之外的余额/熔断 key，也会让写失败拖垮 freeze/settle。
     * 近似裁剪（{@code ~}）按 macro-node 整块裁剪，热路径开销极小；消费滞后期间允许短暂超过该长度。
     */
    private static final int STREAM_MAXLEN = 1_000_000;

    private final RedissonClient redisson;

    public void publish(Map<String, String> entry) {
        try {
            RStream<String, String> stream = redisson.getStream(
                    GatewayConstants.REQUEST_LOG_STREAM_KEY, StringCodec.INSTANCE);
            stream.add(StreamAddArgs.entries(entry).trimNonStrict().maxLen(STREAM_MAXLEN).noLimit());
        } catch (Exception e) {
            log.warn("请求日志投递 Redis Stream 失败 requestId={}: {}", entry.get("requestId"), e.getMessage());
        }
    }
}
