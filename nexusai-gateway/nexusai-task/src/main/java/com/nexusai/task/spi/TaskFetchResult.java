package com.nexusai.task.spi;

/**
 * 异步任务上游 fetch 结果：轮询某进行中任务时上游返回的进度/终态。
 * <p>由 {@link TaskAdapter#fetch} 产出，{@code TaskPoller} 据此驱动 {@code TaskService.markSuccess/markFailed/updateProgress}。
 *
 * @author nexusai
 */
public record TaskFetchResult(Status status, String progress, String resultUrl, String failReason) {

    public enum Status {
        /** 仍在生成（更新进度·不结算） */
        IN_PROGRESS,
        /** 成功（保留 pre-charge·resultUrl 为上游产物地址·OSS 转存由 adapter 自理） */
        SUCCESS,
        /** 失败（终态退款·failReason 落审计） */
        FAILED
    }

    public static TaskFetchResult inProgress(String progress) {
        return new TaskFetchResult(Status.IN_PROGRESS, progress, null, null);
    }

    public static TaskFetchResult success(String resultUrl) {
        return new TaskFetchResult(Status.SUCCESS, "100%", resultUrl, null);
    }

    public static TaskFetchResult failed(String failReason) {
        return new TaskFetchResult(Status.FAILED, null, null, failReason);
    }
}
