package com.nexusai.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexusai.persistence.po.NaiTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 异步任务 Mapper（nai_task）。
 *
 * <p>含多实例并行轮询的「行认领」原语（id 升序游标分页 +
 * MySQL {@code FOR UPDATE SKIP LOCKED} 跳锁）：仅 {@code nexusai.task.poller.mode=claim} 启用，
 * leader 默认模式不触达；详见 {@code com.nexusai.task.TaskClaimer} / {@code TaskPoller}。
 *
 * @author nexusai
 */
@Mapper
public interface NaiTaskMapper extends BaseMapper<NaiTask> {

    /**
     * 认领候选（id 升序游标分页 + 跳锁）：取 status 命中、{@code id > cursor}、租约到期（可重领）的非终态行并加行锁；
     * 已被其它实例锁住的行由 {@code SKIP LOCKED} 跳过——天然按行去重、无需分布式锁。
     * 须在事务内与 {@link #markPollLease} 同用（选中即盖租约后立即提交释放行锁）。
     */
    @Select("""
            <script>
            SELECT id FROM nai_task
            WHERE status IN
            <foreach collection="statuses" item="s" open="(" separator="," close=")">#{s}</foreach>
              AND id &gt; #{cursor}
              AND (poll_lease_until IS NULL OR poll_lease_until &lt; #{now})
            ORDER BY id ASC
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            </script>
            """)
    List<Long> selectClaimableIds(@Param("statuses") List<String> statuses,
                                  @Param("cursor") long cursor,
                                  @Param("now") LocalDateTime now,
                                  @Param("limit") int limit);

    /** 盖租约印章：把已认领行的 {@code poll_lease_until} 推到 {@code now + lease}，本租约窗口内其它实例不再重领。 */
    @Update("""
            <script>
            UPDATE nai_task SET poll_lease_until = #{leaseUntil}
            WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int markPollLease(@Param("ids") List<Long> ids, @Param("leaseUntil") LocalDateTime leaseUntil);

    /**
     * 终态迁移幂等闸门（<b>资金安全·防重复退款</b>）：仅当 task 仍处非终态时把它迁到 {@code toStatus}，返回受影响行数。
     * <p>{@code 返回 1} = 本次调用是「真正的非终态→终态迁移」（赢家），调用方据此<b>恰好一次</b>地执行 settle/退款；
     * {@code 返回 0} = 已被处理过（轮询重领 / 进程重启重处理 / claim 批内租约击穿 / 多实例并行），调用方<b>跳过退款</b>，
     * 杜绝 {@code QuotaLedger.settle} 纯增量被多次调用导致 USD 额度超发（违背 freeze≥settle 铁律）。
     * 单条 UPDATE 自身原子，无需事务；退款置于其后（已提交），崩溃只会少退不会多退（安全方向）。
     */
    @Update("""
            UPDATE nai_task
            SET status = #{toStatus},
                progress = #{progress},
                result_url = #{resultUrl},
                fail_reason = #{failReason}
            WHERE id = #{id} AND status IN ('SUBMITTED', 'IN_PROGRESS')
            """)
    int markTerminal(@Param("id") Long id,
                     @Param("toStatus") String toStatus,
                     @Param("progress") String progress,
                     @Param("resultUrl") String resultUrl,
                     @Param("failReason") String failReason);
}
