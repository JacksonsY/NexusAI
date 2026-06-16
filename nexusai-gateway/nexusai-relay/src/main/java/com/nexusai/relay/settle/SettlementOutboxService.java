package com.nexusai.relay.settle;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.nexusai.billing.quota.QuotaLedger;
import com.nexusai.persistence.mapper.NaiSettlementOutboxMapper;
import com.nexusai.persistence.po.NaiSettlementOutbox;
import com.nexusai.relay.context.RelayContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 结算补偿 outbox（轻量守护线程）：主请求结算失败时落库，后台按 nextRetryAt 重放 QuotaLedger.settle。
 *
 * @author nexusai
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementOutboxService {

    private static final int STATUS_PENDING = 0;
    private static final int STATUS_DONE = 1;
    private static final int STATUS_ABANDONED = 2;
    private static final int STATUS_PROCESSING = 3;
    private static final int DEFAULT_BATCH = 50;
    private static final int MAX_ATTEMPTS = 12;
    private static final long INTERVAL_MS = 15_000L;
    private static final long PROCESSING_LEASE_SECONDS = 120L;

    private final NaiSettlementOutboxMapper outboxMapper;
    private final QuotaLedger quotaLedger;

    @Value("${nexusai.settlement-outbox.enabled:true}")
    private boolean enabled;

    private volatile boolean running = true;
    private Thread worker;

    public void record(RelayContext ctx, long refundMicroUsd, Throwable cause) {
        if (ctx == null || ctx.getToken() == null || refundMicroUsd == 0) {
            return;
        }
        try {
            NaiSettlementOutbox row = new NaiSettlementOutbox();
            row.setRequestId(ctx.getRequestId());
            row.setTokenId(ctx.getToken().getId());
            row.setProjectCode(ctx.getToken().getProjectCode());
            row.setRefundMicroUsd(refundMicroUsd);
            row.setStatus(STATUS_PENDING);
            row.setAttempts(0);
            row.setLastError(truncate(errorMessage(cause), 512));
            row.setNextRetryAt(LocalDateTime.now());
            outboxMapper.insert(row);
        } catch (Exception e) {
            log.error("结算 outbox 落库失败 requestId={} refund={}", ctx.getRequestId(), refundMicroUsd, e);
        }
    }

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("结算 outbox 重试器未启用");
            return;
        }
        worker = new Thread(this::loop, "settlement-outbox-retry");
        worker.setDaemon(true);
        worker.start();
        log.info("结算 outbox 重试器启动");
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void loop() {
        while (running) {
            try {
                Thread.sleep(INTERVAL_MS);
                retryDueOnce(DEFAULT_BATCH);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("结算 outbox 重试异常", e);
            }
        }
        log.info("结算 outbox 重试器停止");
    }

    void retryDueOnce(int limit) {
        List<NaiSettlementOutbox> rows = outboxMapper.selectList(new LambdaQueryWrapper<NaiSettlementOutbox>()
                .in(NaiSettlementOutbox::getStatus, STATUS_PENDING, STATUS_PROCESSING)
                .le(NaiSettlementOutbox::getNextRetryAt, LocalDateTime.now())
                .orderByAsc(NaiSettlementOutbox::getId)
                .last("limit " + Math.max(1, limit)));
        for (NaiSettlementOutbox row : rows) {
            retryOne(row);
        }
    }

    private void retryOne(NaiSettlementOutbox row) {
        LocalDateTime now = LocalDateTime.now();
        int attempts = (row.getAttempts() == null ? 0 : row.getAttempts()) + 1;
        int won = outboxMapper.update(null, new LambdaUpdateWrapper<NaiSettlementOutbox>()
                .eq(NaiSettlementOutbox::getId, row.getId())
                .eq(NaiSettlementOutbox::getStatus, row.getStatus())
                .le(NaiSettlementOutbox::getNextRetryAt, now)
                .set(NaiSettlementOutbox::getStatus, STATUS_PROCESSING)
                .set(NaiSettlementOutbox::getAttempts, attempts)
                .set(NaiSettlementOutbox::getNextRetryAt, now.plusSeconds(PROCESSING_LEASE_SECONDS)));
        if (won == 0) {
            return;
        }
        row.setAttempts(attempts);
        try {
            // 🔴 幂等结算：dedup="settle:"+requestId（与内联 settleWithRetry/refundAll 同 id）。
            // 内联结算若已应用但 ACK 丢失才落 outbox，此处用同一 dedup 标记重放——命中标记整体 no-op，
            // 杜绝「内联已扣 + outbox 再扣」的跨路径双扣/双退；标记缺失（内联确未应用）则正常补结算一次。
            quotaLedger.settleByTokenIdOnce(row.getTokenId(), row.getRefundMicroUsd(),
                    "settle:" + row.getRequestId());
            row.setStatus(STATUS_DONE);
            row.setLastError(null);
            outboxMapper.updateById(row);
        } catch (Exception e) {
            row.setLastError(truncate(errorMessage(e), 512));
            row.setNextRetryAt(LocalDateTime.now().plusSeconds(backoffSeconds(attempts)));
            row.setStatus(STATUS_PENDING);
            if (attempts >= MAX_ATTEMPTS) {
                row.setStatus(STATUS_ABANDONED);
                log.error("结算 outbox 重试放弃 requestId={} tokenId={} refund={} attempts={}",
                        row.getRequestId(), row.getTokenId(), row.getRefundMicroUsd(), attempts, e);
            }
            outboxMapper.updateById(row);
        }
    }

    private static long backoffSeconds(int attempts) {
        return Math.min(3600L, 5L * (1L << Math.min(attempts, 8)));
    }

    private static String errorMessage(Throwable cause) {
        if (cause == null) {
            return null;
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
