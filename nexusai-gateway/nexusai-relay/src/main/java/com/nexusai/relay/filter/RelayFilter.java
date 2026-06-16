package com.nexusai.relay.filter;

import com.nexusai.relay.context.RelayContext;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;

/**
 * 转发闸门链单元（relay 为服务链）。各 Filter 按 {@link Ordered} 顺序<b>同步</b>执行于
 * boundedElastic（不阻塞 Netty 事件循环），读写共享 {@link RelayContext}；任一步抛 {@code GatewayException}
 * 即中断、由 orchestrator 转 OpenAI 兼容错误体。预扣（QuotaGateFilter）排最后——前置任何失败都在扣 USD 额度之前，无需回退。
 * <p>链式设计便于按阶段插拔新闸门（Model Permission 已落 P1；Canonical 互转留 P4）。
 *
 * @author nexusai
 */
public interface RelayFilter extends Ordered {

    /** 标准顺位间隔 10，便于中间插入新 Filter。 */
    int ORDER_AUTH = 10;
    int ORDER_PEEK = 20;
    int ORDER_MODEL_ALIAS = 25;
    /** 护栏排在别名归一(25)之后、模型权限(30)之前·独立顺位避免与 ModelAliasFilter 同序导致执行序不确定。 */
    int ORDER_GUARDRAIL = 27;
    int ORDER_MODEL_PERMISSION = 30;
    int ORDER_TOKEN_ACCESS = 35;
    int ORDER_RATE_LIMIT = 40;
    int ORDER_ROUTE = 50;
    int ORDER_PROVIDER_ADAPT = 60;
    int ORDER_QUOTA_GATE = 70;

    /**
     * 执行本闸门：成功则推进 ctx；失败抛 {@code GatewayException}。
     *
     * @param ctx      贯穿管线的上下文（输入：requestId/path/clientIp/body；各 Filter 逐步填充）
     * @param exchange 原始请求交换（读 header / 远端地址等）
     */
    void filter(RelayContext ctx, ServerWebExchange exchange);
}
