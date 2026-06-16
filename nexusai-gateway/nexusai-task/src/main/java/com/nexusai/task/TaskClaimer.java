package com.nexusai.task;

import com.nexusai.persistence.mapper.NaiTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务行认领器（claim 模式 · 横向扩展）：在<b>同一事务</b>内
 * {@code SELECT ... FOR UPDATE SKIP LOCKED}（{@link NaiTaskMapper#selectClaimableIds}）抢一批不相交的行，
 * 立即盖租约（{@link NaiTaskMapper#markPollLease}）后提交释放行锁——上游 fetch 在事务外执行，<b>不长持行锁</b>。
 * 跳锁 + 租约双保险：同窗口内每行只被一个实例处理；leader 宕机不影响（无单点）。
 *
 * <p>须为独立 bean（非 {@code TaskPoller} 自调用），{@link Transactional} 代理才生效。
 *
 * @author nexusai
 */
@Component
@RequiredArgsConstructor
public class TaskClaimer {

    /** 非终态：仍需向上游轮询的状态。 */
    private static final List<String> NON_TERMINAL = List.of("SUBMITTED", "IN_PROGRESS");

    private final NaiTaskMapper taskMapper;

    /**
     * 认领一批：SKIP LOCKED 抢行 + 盖租约，返回已认领任务 id（升序）；空表示该游标之后本轮无可领行。
     *
     * @param cursor       id 游标（取上批 maxId + 1，PartitionTask 升序推进，避免重扫）
     * @param leaseSeconds 租约秒数（须 > 单任务上游 fetch 耗时，防租约内被他实例重领）
     * @param limit        单批认领上限
     */
    @Transactional
    public List<Long> claimBatch(long cursor, long leaseSeconds, int limit) {
        LocalDateTime now = LocalDateTime.now();
        List<Long> ids = taskMapper.selectClaimableIds(NON_TERMINAL, cursor, now, limit);
        if (!ids.isEmpty()) {
            taskMapper.markPollLease(ids, now.plusSeconds(leaseSeconds));
        }
        return ids;
    }
}
