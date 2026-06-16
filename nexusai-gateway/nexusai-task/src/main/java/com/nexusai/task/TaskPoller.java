package com.nexusai.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexusai.persistence.mapper.NaiTaskMapper;
import com.nexusai.persistence.po.NaiTask;
import com.nexusai.task.spi.TaskAdapterRegistry;
import com.nexusai.task.spi.TaskFetchResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 异步任务轮询器：守护线程扫描非终态任务 → 查上游状态 → 更新进度/终态（TaskService 结算/退款）。
 *
 * <p><b>两种多实例去重模式（{@code nexusai.task.poller.mode}，均不引重型分布式调度器·用进程内轮询）</b>：
 * <ul>
 *   <li><b>leader（默认）</b>：每周期用 Redisson {@link RLock} 抢 leader 锁（非阻塞 {@code tryLock(0,…)} + 租约 TTL
 *       宕机自动易主），赢家单实例串行轮询。Redis 锁 + TTL 故障转移 / 单点 master 范式。
 *       简单、零额外列、单实例/小规模够用——nexusai 刻意默认它。</li>
 *   <li><b>claim（量大再上·P1）</b>：所有实例并行，各自用 {@code FOR UPDATE SKIP LOCKED} + 租约认领不相交行批
 *       （{@link TaskClaimer}），按 id 升序游标推进（分页范式）。
 *       无 leader 单点、吞吐随实例数近线性扩展。<b>要求 MySQL 8.0.1+ / MariaDB 10.6+（SKIP LOCKED 语法）。</b></li>
 * </ul>
 * 两模式上游 fetch 集成点一致（per-provider 后续）。<b>资金安全不依赖租约/leader 锁这种弱去重</b>：
 * 租约/锁仅防重复劳动，真正的 exactly-once 退款由 {@link TaskService#markFailed} 的终态 CAS 闸门
 * （{@code NaiTaskMapper.markTerminal} 仅赢家退款一次）保证——故租约超时被重领也不会重复结算。
 * <p>注：{@code poll_lease_until} 列两种模式都必须存在（PO 字段进默认 SELECT），存量库须 ALTER 补列。
 *
 * @author nexusai
 */
@Slf4j
@Component
public class TaskPoller {

    private static final long INTERVAL_MS = 5_000L;

    /** leader 选举锁 key（Redis 锁范式）。 */
    private static final String LEADER_LOCK = "nai:task:poller:leader";

    /**
     * claim 模式单批认领上限与本轮最大批数（id 游标推进的回合上限·防失控）。
     * 🔴 整批在<b>同一租约</b>下串行 fetch 上游，故批量须足够小，使「批量 × 单任务 fetch 耗时」落在 leaseSeconds 内，
     * 否则批中后段任务在租约过期后被另一实例重领、重复轮询上游（资金仍由终态 CAS 兜住·只是浪费上游调用）。
     * 取 20 而非 100：lease 默认 30s 下留 ~1.5s/任务预算；上游更慢时应同步调大 nexusai.task.poller.lease-seconds。
     */
    private static final int CLAIM_BATCH = 20;
    private static final int CLAIM_MAX_ROUNDS = 1000;

    private final NaiTaskMapper taskMapper;
    private final RedissonClient redisson;
    private final TaskClaimer taskClaimer;
    private final TaskService taskService;
    private final TaskAdapterRegistry adapterRegistry;

    /** true=claim（SKIP LOCKED 行认领）；false=leader（Redisson 锁单点）。 */
    private final boolean claimMode;

    /** 锁/认领租约秒数：须 &gt; 单轮（leader）或<b>单批 = CLAIM_BATCH × 单任务 fetch</b>（claim）耗时；到期自动可重领/易主。 */
    private final long leaseSeconds;

    private volatile boolean running = true;
    private Thread worker;

    public TaskPoller(NaiTaskMapper taskMapper, RedissonClient redisson, TaskClaimer taskClaimer,
                      TaskService taskService, TaskAdapterRegistry adapterRegistry,
                      @Value("${nexusai.task.poller.mode:leader}") String mode,
                      @Value("${nexusai.task.poller.lease-seconds:30}") long leaseSeconds) {
        this.taskMapper = taskMapper;
        this.redisson = redisson;
        this.taskClaimer = taskClaimer;
        this.taskService = taskService;
        this.adapterRegistry = adapterRegistry;
        this.claimMode = "claim".equalsIgnoreCase(mode == null ? "" : mode.trim());
        this.leaseSeconds = leaseSeconds > 0 ? leaseSeconds : 30L;
    }

    @PostConstruct
    public void start() {
        worker = new Thread(this::loop, "task-poller");
        worker.setDaemon(true);
        worker.start();
        log.info("异步任务轮询器启动（mode={} · 进程内轮询·不引重型分布式调度器）",
                claimMode ? "claim/SKIP-LOCKED" : "leader/Redisson锁");
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
                if (claimMode) {
                    pollByClaim();
                } else {
                    pollOnceAsLeader();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("任务轮询异常", e);
            }
        }
        log.info("异步任务轮询器停止");
    }

    /**
     * leader 模式：仅持锁实例本轮轮询（Redis 锁 + TTL 范式 · 自动故障转移）。
     * 非阻塞抢锁，抢不到说明另一实例在跑，本轮直接跳过。
     */
    private void pollOnceAsLeader() throws InterruptedException {
        RLock lock = redisson.getLock(LEADER_LOCK);
        if (!lock.tryLock(0, leaseSeconds, TimeUnit.SECONDS)) {
            return;
        }
        try {
            List<NaiTask> pending = taskMapper.selectList(new LambdaQueryWrapper<NaiTask>()
                    .in(NaiTask::getStatus, "SUBMITTED", "IN_PROGRESS")
                    .last("limit 100"));
            process(pending);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * claim 模式：本实例并行认领不相交行批（SKIP LOCKED + 租约），id 升序游标推进直到本轮无可领行。
     * 认领（短事务·{@link TaskClaimer}）与处理（事务外·上游 fetch）分离，避免长持行锁。
     */
    private void pollByClaim() {
        long cursor = 0L;
        int rounds = 0;
        while (running && rounds++ < CLAIM_MAX_ROUNDS) {
            List<Long> ids = taskClaimer.claimBatch(cursor, leaseSeconds, CLAIM_BATCH);
            if (ids.isEmpty()) {
                break;
            }
            List<NaiTask> claimed = taskMapper.selectList(new LambdaQueryWrapper<NaiTask>()
                    .in(NaiTask::getId, ids));
            process(claimed);
            cursor = ids.get(ids.size() - 1) + 1;   // PartitionTask：游标推进到 maxId + 1
        }
    }

    /**
     * 处理一批进行中任务：经 {@link TaskAdapterRegistry} 按模态路由到具体 {@code TaskAdapter} 查上游进度/终态 →
     * 驱动 {@code TaskService.markSuccess/markFailed/updateProgress}。无对应 adapter 说明任务无法被真正提交/轮询，
     * 直接转 FAILED，避免 SUBMITTED/IN_PROGRESS 永久悬挂。
     * 单任务异常隔离（不影响同批其余）；退款一次性由 markFailed 终态 CAS 保证。
     */
    private void process(List<NaiTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        for (NaiTask t : tasks) {
            try {
                var adapter = adapterRegistry.resolve(t.getModality());
                if (adapter.isEmpty()) {
                    taskService.markFailed(t.getTaskId(), "NO_TASK_ADAPTER: modality=" + t.getModality());
                    continue;
                }
                TaskFetchResult r = adapter.get().fetch(t);
                switch (r.status()) {
                    case SUCCESS -> taskService.markSuccess(t.getTaskId(), r.resultUrl());
                    case FAILED -> taskService.markFailed(t.getTaskId(), r.failReason());
                    case IN_PROGRESS -> taskService.updateProgress(t.getTaskId(), r.progress());
                }
            } catch (Exception e) {
                log.warn("任务 {} 上游 fetch 异常（保留进行中·待下轮重试）", t.getTaskId(), e);
            }
        }
    }
}
