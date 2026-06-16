package com.nexusai.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexusai.billing.quota.QuotaLedger;
import com.nexusai.billing.money.UsdAmount;
import com.nexusai.persistence.mapper.NaiTaskMapper;
import com.nexusai.persistence.po.NaiToken;
import com.nexusai.persistence.po.NaiTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 异步任务生命周期服务：submit 持久化 + pre-charge 冻结；终态 SUCCESS 保留扣费 / FAILED 全额退款。
 * 上游 submit/fetch 协议适配（MJ proxy / 视频 API）+ OSS 转存为集成点（per-provider 后续）。
 * 多实例轮询去重见 {@link TaskPoller}（leader Redisson 锁默认 / claim FOR UPDATE SKIP LOCKED 横扩·<b>不引重型分布式调度器</b>）。
 *
 * @author nexusai
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final NaiTaskMapper taskMapper;
    private final QuotaLedger quotaLedger;

    /** 提交：持久化 SUBMITTED 任务（USD 已由调用方冻结，frozenUsd 记账·入参 micro-USD）。 */
    public NaiTask submit(NaiToken token, String modality, String action, String model,
                          String submitBody, long frozenUsdAmount) {
        NaiTask task = new NaiTask();
        task.setTaskId("task-" + UUID.randomUUID().toString().replace("-", ""));
        task.setProjectCode(token.getProjectCode());
        task.setTokenId(token.getId());
        task.setModality(modality);
        task.setAction(action);
        task.setModel(model);
        task.setStatus("SUBMITTED");
        task.setFrozenUsd(UsdAmount.fromMicros(frozenUsdAmount));
        task.setSubmitBody(submitBody);
        taskMapper.insert(task);
        return task;
    }

    public NaiTask get(String taskId) {
        return taskMapper.selectOne(new LambdaQueryWrapper<NaiTask>().eq(NaiTask::getTaskId, taskId));
    }

    public void bindUpstream(String taskId, Long channelId, Long keyId, String upstreamTaskId) {
        NaiTask t = get(taskId);
        if (t == null) {
            return;
        }
        t.setChannelId(channelId);
        t.setKeyId(keyId);
        t.setUpstreamTaskId(upstreamTaskId);
        t.setStatus("IN_PROGRESS");
        taskMapper.updateById(t);
    }

    /** 更新进行中进度（非终态·不结算·终态任务忽略）。 */
    public void updateProgress(String taskId, String progress) {
        NaiTask t = get(taskId);
        if (t == null || "SUCCESS".equals(t.getStatus()) || "FAILED".equals(t.getStatus())) {
            return;
        }
        t.setProgress(progress);
        t.setStatus("IN_PROGRESS");
        taskMapper.updateById(t);
    }

    public void markSuccess(String taskId, String resultUrl) {
        NaiTask t = get(taskId);
        if (t == null) {
            return;
        }
        // 幂等终态闸门：仅赢得「非终态→SUCCESS」迁移者继续；重领/重启/租约击穿的重复调用 won==0 直接跳过
        int won = taskMapper.markTerminal(t.getId(), "SUCCESS", "100%", resultUrl, null);
        if (won == 0) {
            log.info("任务 {} 已终态，幂等跳过重复处理（重领/重启/并行）", taskId);
            return;
        }
        // 成功：保留 pre-charge（实际计费在 业务面 按结果计；此处不退）
        log.info("任务成功 taskId={}", taskId);
    }

    public void markFailed(String taskId, String reason) {
        NaiTask t = get(taskId);
        if (t == null) {
            return;
        }
        // 幂等终态闸门（资金安全·防重复退款）：仅赢得「非终态→FAILED」迁移者退款一次；
        // 重领/重启/claim 批内租约击穿/多实例并行导致的重复调用 won==0 → 跳过 settle，杜绝 USD 额度超发
        int won = taskMapper.markTerminal(t.getId(), "FAILED", null, null, reason);
        if (won == 0) {
            log.info("任务 {} 已终态，幂等跳过重复退款（防超发·freeze≥settle）", taskId);
            return;
        }
        // 退款置于终态迁移（已提交）之后：崩溃只会少退不会多退（安全方向）；settle 至多一次
        if (t.getTokenId() != null && t.getFrozenUsd() != null && t.getFrozenUsd().signum() > 0) {
            refundWithRetry(taskId, t.getTokenId(), UsdAmount.toMicros(t.getFrozenUsd()));
        }
        log.info("任务失败 taskId={} reason={}，已退冻结 USD={}", taskId, reason, t.getFrozenUsd());
    }

    /** 任务退款有界重试上限与退避基数（对齐 HTTP 路径 SettlementService·Redis 抖动下不丢退款）。 */
    private static final int REFUND_MAX_ATTEMPTS = 3;
    private static final long REFUND_BACKOFF_MS = 50L;

    /**
     * 任务失败退款（有界重试 + 幂等去重）。
     * <p>🔴 旧实现单次 {@code settleByTokenId}（非幂等·无重试/兜底）：终态 CAS 已赢但 settle 抛异常时退款永久丢失。
     * 改用 {@code settleByTokenIdOnce}（dedup={@code task:taskId}·Lua 原子去重·重试绝不双退）+ 有界重试；耗尽仍失败落
     * {@code TASK_REFUND_UNSETTLED} CRITICAL 供对账（与同步路径 {@code COST_UNSETTLED} 同语义）。
     */
    private void refundWithRetry(String taskId, Long tokenId, long refund) {
        String dedup = "task:" + taskId;
        for (int attempt = 1; attempt <= REFUND_MAX_ATTEMPTS; attempt++) {
            try {
                quotaLedger.settleByTokenIdOnce(tokenId, refund, dedup);
                return;
            } catch (Exception e) {
                if (attempt == REFUND_MAX_ATTEMPTS) {
                    log.error("🔴TASK_REFUND_UNSETTLED taskId={} tokenId={} refund={} 退款 {} 次仍失败，需人工对账（freeze 滞留）",
                            taskId, tokenId, refund, REFUND_MAX_ATTEMPTS, e);
                    return;
                }
                try {
                    Thread.sleep(REFUND_BACKOFF_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.error("🔴TASK_REFUND_UNSETTLED 退款重试被中断 taskId={} tokenId={} refund={}", taskId, tokenId, refund);
                    return;
                }
            }
        }
    }
}
