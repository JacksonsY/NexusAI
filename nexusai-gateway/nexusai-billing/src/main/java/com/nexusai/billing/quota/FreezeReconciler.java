package com.nexusai.billing.quota;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * 冻结崩溃对账（HTTP relay 数据面专用）：freeze 成功后登记 {@code (requestId, tokenId, frozen, ts)}，正常 settle
 * 完成即注销。进程在 <b>freeze 与 settle 之间</b>崩溃（kill-9/OOM/断电·长流式窗口数十秒~分钟）时，该冻结既无内联
 * settle 也无 outbox 行（都未来得及创建）→ 客户被预扣却未获服务、额度永久滞留且无结构化记录。本对账器周期扫描
 * 超阈值仍未注销的孤儿冻结，复用与正常路径<b>同一去重 id</b>（{@code "settle:"+requestId}）全额退款——与
 * 内联 settle / outbox 重放 / refundAll 互斥（SET NX 同 Lua），<b>绝不双退</b>。
 *
 * <p>登记用单个 ZSET（member={@code requestId:tokenId:frozen}·score=freeze epochMillis），按 age 高效范围查询、
 * 集群友好（固定 key）。对账阈值 {@link #RECONCILE_AGE_MS} 远超流式 drain 上限，且 &lt; 内联 dedup TTL——即便某条
 * 注销遗漏，已结算记录的 dedup 标记仍在、对账退款命中标记 no-op，双保险。
 *
 * <p>任务 / realtime 各有持久任务行 / 自有结算（durability 自足·且 settle dedup 口径不同），<b>不经本对账器</b>。
 *
 * @author nexusai
 */
@Slf4j
@Component
public class FreezeReconciler {

    /** 冻结登记 ZSET（member=requestId:tokenId:frozen·score=freeze epochMillis）。 */
    private static final String PENDING_ZSET = "nai:usd:freeze:pending";
    /** 对账 leader 锁（多实例仅一个扫描·幂等退款下即便双主也不双退·锁仅省重复劳动）。 */
    private static final String LEADER_LOCK = "nai:usd:freeze:reconciler:leader";
    private static final long INTERVAL_MS = 60_000L;
    private static final long LEASE_SECONDS = 120L;
    /**
     * 仅对账 &gt; 5 分钟仍未注销的冻结：远超流式 drain 上限(180s)+重试，杜绝把在途请求误判为孤儿而超额退款；
     * 又 &lt; 内联 settle dedup TTL(600s)——已结算记录若注销遗漏，其 dedup 标记仍在、对账退款命中即 no-op。
     */
    private static final long RECONCILE_AGE_MS = 300_000L;
    /** 单轮处理上限（孤儿堆积时分轮消化·防一次扫太多）。 */
    private static final int RECONCILE_BATCH = 500;

    private final RedissonClient redisson;
    private final QuotaLedger quotaLedger;

    private volatile boolean running = true;
    private Thread worker;

    public FreezeReconciler(RedissonClient redisson, QuotaLedger quotaLedger) {
        this.redisson = redisson;
        this.quotaLedger = quotaLedger;
    }

    /** freeze 成功后登记（frozen≤0 不登记·如免费/unlimited 请求 freeze 为 0）。 */
    public void record(String requestId, long tokenId, long frozenMicro, long epochMillis) {
        if (requestId == null || frozenMicro <= 0) {
            return;
        }
        zset().add((double) epochMillis, member(requestId, tokenId, frozenMicro));
    }

    /** 正常 settle 成功后注销（三元与 record 一致·SettlementService 持 ctx.requestId/token.id/frozenUsdAmount）。 */
    public void clear(String requestId, long tokenId, long frozenMicro) {
        if (requestId == null || frozenMicro <= 0) {
            return;
        }
        zset().remove(member(requestId, tokenId, frozenMicro));
    }

    private RScoredSortedSet<String> zset() {
        return redisson.getScoredSortedSet(PENDING_ZSET, StringCodec.INSTANCE);
    }

    private static String member(String requestId, long tokenId, long frozenMicro) {
        return requestId + ":" + tokenId + ":" + frozenMicro;
    }

    @PostConstruct
    public void start() {
        worker = new Thread(this::loop, "freeze-reconciler");
        worker.setDaemon(true);
        worker.start();
        log.info("冻结崩溃对账器启动（进程内·leader 锁·仅 HTTP relay 冻结）");
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
                reconcileOnceAsLeader();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("冻结对账异常", e);
            }
        }
        log.info("冻结崩溃对账器停止");
    }

    private void reconcileOnceAsLeader() throws InterruptedException {
        RLock lock = redisson.getLock(LEADER_LOCK);
        if (!lock.tryLock(0, LEASE_SECONDS, TimeUnit.SECONDS)) {
            return;
        }
        try {
            long cutoff = System.currentTimeMillis() - RECONCILE_AGE_MS;
            Collection<String> orphans =
                    zset().valueRange(Double.NEGATIVE_INFINITY, true, (double) cutoff, true, 0, RECONCILE_BATCH);
            for (String m : orphans) {
                String[] p = m.split(":");
                if (p.length != 3) {
                    zset().remove(m);   // 脏数据·清理
                    continue;
                }
                String requestId = p[0];
                long tokenId;
                long frozen;
                try {
                    tokenId = Long.parseLong(p[1]);
                    frozen = Long.parseLong(p[2]);
                } catch (NumberFormatException nfe) {
                    zset().remove(m);
                    continue;
                }
                try {
                    // 孤儿冻结全额退：dedup="settle:"+requestId 与正常内联/outbox/refundAll 互斥 → 绝不双退
                    quotaLedger.settleByTokenIdOnce(tokenId, frozen, "settle:" + requestId);
                    zset().remove(m);
                    log.warn("🔴冻结崩溃对账：孤儿冻结全额退款 requestId={} tokenId={} frozen={}（进程曾在 freeze 与 settle 间崩溃）",
                            requestId, tokenId, frozen);
                } catch (Exception e) {
                    log.error("冻结对账退款失败 requestId={} tokenId={} frozen={}（保留·下轮重试）", requestId, tokenId, frozen, e);
                }
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
