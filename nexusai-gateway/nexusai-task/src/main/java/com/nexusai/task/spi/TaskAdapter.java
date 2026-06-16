package com.nexusai.task.spi;

import com.nexusai.persistence.po.NaiTask;

/**
 * 异步任务上游适配 SPI（per-provider）：MJ proxy / 视频 API 等「先 submit 拿 upstreamTaskId、再轮询 fetch
 * 进度/终态」类上游的协议适配承接点。每个上游协议家族实现一个 {@code @Component}，{@link TaskAdapterRegistry} 按
 * modality 路由；OSS 转存（把上游临时产物落对象存储）由具体 adapter 在 SUCCESS 前自理。
 *
 * <p><b>资金安全</b>：fetch 仅决定状态/产物，退款一次性由 {@code TaskService.markFailed} 的终态 CAS 闸门保证
 * （重领/重启不重复退）；adapter 实现应幂等、无副作用地查询上游。
 *
 * @author nexusai
 */
public interface TaskAdapter {

    /** 是否处理该模态（如 image/video）。 */
    boolean supports(String modality);

    /**
     * 查询上游任务当前状态（无副作用·可重复调用）。
     * 实现自行解密绑定 Key 凭证 + 用 {@link NaiTask#getUpstreamTaskId()} 调上游进度接口。
     */
    TaskFetchResult fetch(NaiTask task);
}
