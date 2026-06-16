package com.nexusai.control.domain.platform.task.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nexusai.common.model.PageQuery;
import com.nexusai.common.model.PageResult;
import com.nexusai.common.model.R;
import com.nexusai.control.domain.platform.task.vo.TaskStatsVo;
import com.nexusai.persistence.mapper.NaiTaskMapper;
import com.nexusai.persistence.po.NaiTask;
import com.nexusai.task.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 异步任务管理端点（运维台「异步任务」）：
 * 列表多维筛选 / 状态聚合 stats / 详情 / 取消（复用 {@link TaskService#markFailed} 幂等终态闸门，自动全额退款）。
 * 注意 relay 的 /v1/tasks 走 sk- 流量鉴权且只见调用方自己的任务；本端点提供控制面会话/M2M 全局视图。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/tasks")
@RequiredArgsConstructor
public class TaskAdminController {

    private final NaiTaskMapper taskMapper;
    private final TaskService taskService;

    @GetMapping
    public Mono<R<PageResult<NaiTask>>> list(@RequestParam(defaultValue = "1") long pageNum,
                                             @RequestParam(defaultValue = "10") long pageSize,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String modality,
                                             @RequestParam(required = false) String taskId,
                                             @RequestParam(required = false) String model,
                                             @RequestParam(required = false) Long channelId,
                                             @RequestParam(required = false) String beginTime,
                                             @RequestParam(required = false) String endTime) {
        PageQuery pq = PageQuery.of(pageNum, pageSize);
        return Mono.fromCallable(() -> {
            Page<NaiTask> page = taskMapper.selectPage(new Page<>(pq.getPageNum(), pq.getPageSize()),
                    Wrappers.lambdaQuery(NaiTask.class)
                            .eq(StringUtils.hasText(status), NaiTask::getStatus, status)
                            .eq(StringUtils.hasText(modality), NaiTask::getModality, modality)
                            .eq(channelId != null, NaiTask::getChannelId, channelId)
                            .like(StringUtils.hasText(taskId), NaiTask::getTaskId, taskId)
                            .like(StringUtils.hasText(model), NaiTask::getModel, model)
                            .ge(StringUtils.hasText(beginTime), NaiTask::getCreateDt, beginTime)
                            .le(StringUtils.hasText(endTime), NaiTask::getCreateDt, endTime)
                            .orderByDesc(NaiTask::getId));
            return R.ok(PageResult.of(page.getRecords(), page.getTotal(), pq.getPageNum(), pq.getPageSize()));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 状态聚合统计（顶部统计卡：总数/处理中/已完成/已失败）。 */
    @GetMapping("/stats")
    public Mono<R<TaskStatsVo>> stats() {
        return Mono.fromCallable(() -> {
            QueryWrapper<NaiTask> qw = new QueryWrapper<NaiTask>()
                    .select("status", "count(*) as cnt")
                    .groupBy("status");
            List<Map<String, Object>> rows = taskMapper.selectMaps(qw);
            Map<String, Long> byStatus = new LinkedHashMap<>();
            long total = 0;
            for (Map<String, Object> row : rows) {
                String st = String.valueOf(row.get("status"));
                long cnt = ((Number) row.get("cnt")).longValue();
                byStatus.put(st, cnt);
                total += cnt;
            }
            long running = byStatus.getOrDefault("SUBMITTED", 0L) + byStatus.getOrDefault("IN_PROGRESS", 0L);
            long succeeded = byStatus.getOrDefault("SUCCESS", 0L);
            long failed = byStatus.getOrDefault("FAILED", 0L);
            return R.ok(new TaskStatsVo(total, running, succeeded, failed, byStatus));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 任务详情（含 submitBody 原始请求体·详情抽屉用）。 */
    @GetMapping("/{taskId}")
    public Mono<R<NaiTask>> detail(@PathVariable String taskId) {
        return Mono.fromCallable(() -> {
            NaiTask task = taskService.get(taskId);
            return task == null ? R.<NaiTask>fail("任务不存在: " + taskId) : R.ok(task);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 取消任务（仅非终态有效）：复用 markFailed 幂等终态闸门 → 自动全额退冻结额度（USD）；
     * 已终态的重复取消由 CAS 闸门幂等跳过（防重复退款）。
     */
    @PostMapping("/{taskId}/cancel")
    public Mono<R<Void>> cancel(@PathVariable String taskId) {
        return Mono.fromCallable(() -> {
            NaiTask task = taskService.get(taskId);
            if (task == null) {
                return R.<Void>fail("任务不存在: " + taskId);
            }
            if ("SUCCESS".equals(task.getStatus()) || "FAILED".equals(task.getStatus())) {
                return R.<Void>fail("任务已终态（" + task.getStatus() + "），无法取消");
            }
            taskService.markFailed(taskId, "管理员取消");
            return R.<Void>ok();
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
