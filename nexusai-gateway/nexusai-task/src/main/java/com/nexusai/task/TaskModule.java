package com.nexusai.task;

/**
 * 异步任务模块包说明（MJ/视频）。已实装：{@code TaskRelayService}(submit→pre-charge 冻结) +
 * {@code TaskPoller}(进程内轮询·<b>不引重型分布式调度器</b>·leader/claim 双模式·{@code FOR UPDATE SKIP LOCKED} 行认领防多实例重复·
 * 终态 DB-CAS {@code markTerminal} 保 exactly-once 退款)。结果 URL 转存（大文件不双向穿透网关）留后续。
 *
 * @author nexusai
 */
public final class TaskModule {

    private TaskModule() {
    }
}
