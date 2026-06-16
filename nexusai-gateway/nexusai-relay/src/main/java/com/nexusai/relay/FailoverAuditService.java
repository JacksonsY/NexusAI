package com.nexusai.relay;

import com.nexusai.persistence.mapper.NaiFailoverEventMapper;
import com.nexusai.persistence.po.NaiFailoverEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 故障转移/重试事件审计（对抗验证 H7）：把 {@code RelayOrchestrator} 的换渠道/换 Key 重试与重试耗尽事件
 * <b>fire-and-forget</b> 落 {@code nai_failover_event}，使失败链请求结束后仍可审计。
 * <p>纪律：①热路径外（boundedElastic）异步落库；②内部吞异常——审计失败<b>绝不</b>影响主请求。
 *
 * @author nexusai
 */
@Service
@RequiredArgsConstructor
public class FailoverAuditService {

    private static final Logger log = LoggerFactory.getLogger(FailoverAuditService.class);

    private final NaiFailoverEventMapper mapper;

    /** 记一次转移事件（retry=换号继续 / exhausted=重试耗尽终态）。异步、永不抛。 */
    public void record(String requestId, int attempt, Long fromChannelId, Long fromKeyId,
                       String errorCode, String reason, String outcome) {
        Mono.fromRunnable(() -> {
                    try {
                        NaiFailoverEvent e = new NaiFailoverEvent();
                        e.setRequestId(requestId);
                        e.setAttempt(attempt);
                        e.setFromChannelId(fromChannelId);
                        e.setFromKeyId(fromKeyId);
                        e.setErrorCode(errorCode);
                        e.setReason(reason);
                        e.setOutcome(outcome);
                        mapper.insert(e);
                    } catch (Exception ex) {
                        log.warn("故障转移审计落库失败（不影响主请求）requestId={}: {}", requestId, ex.toString());
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }
}
