package com.nexusai.relay.upstream;

import com.nexusai.common.exception.GatewayErrorCode;

/**
 * 选号后、非上游故障的"换一个再试"信号：排除指定渠道/Key 改选，<b>不罚健康</b>（非上游过错）；
 * 重试耗尽则以 exhaustCode 干净终态报错 + 退 freeze。用于：① 流式跨协议未支持（排渠道）；
 * ② 凭证解密/Provider 适配失败（排该 Key，触发同渠道换号失败转移）；③ 项目渠道策略不允许（排渠道）。
 * <p>槽释放/route 清理统一由编排器（信号捕获方）处理——抛出方不操作路由生命周期。
 *
 * @author nexusai
 */
public final class SkipAndRetryException extends RuntimeException {

    private final Long excludeChannelId;
    private final Long excludeKeyId;
    private final GatewayErrorCode exhaustCode;

    public SkipAndRetryException(Long excludeChannelId, Long excludeKeyId, GatewayErrorCode exhaustCode, String msg) {
        super(msg);
        this.excludeChannelId = excludeChannelId;
        this.excludeKeyId = excludeKeyId;
        this.exhaustCode = exhaustCode;
    }

    public Long excludeChannelId() {
        return excludeChannelId;
    }

    public Long excludeKeyId() {
        return excludeKeyId;
    }

    public GatewayErrorCode exhaustCode() {
        return exhaustCode;
    }
}
