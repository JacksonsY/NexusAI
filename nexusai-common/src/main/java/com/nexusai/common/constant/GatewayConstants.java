package com.nexusai.common.constant;

/**
 * 网关常量（魔法值禁令 → 集中常量）。
 *
 * @author nexusai
 */
public final class GatewayConstants {

    private GatewayConstants() {
    }

    /** OpenAI 兼容鉴权头 */
    public static final String AUTH_HEADER = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";
    /** nexusai 签发的 API Key 前缀 */
    public static final String API_KEY_PREFIX = "sk-";

    /** 控制面服务间鉴权头（业务面 → 网关 admin API） */
    public static final String ADMIN_TOKEN_HEADER = "NexusAI-Admin-Token";

    /** 默认分组 */
    public static final String DEFAULT_GROUP = "default";

    /** RelayContext 透传属性键 */
    public static final String CTX_ATTR = "nexusai.relay.ctx";

    /** 控制面调用方受限项目码（项目作用域令牌时由 CallerScopeWebFilter 写入·/admin 控制器据此做 body 级 projectCode 同项目校验·空=平台/admin 不限） */
    public static final String CALLER_PROJECT_ATTR = "nexusai.caller.project";

    /** 控制台「项目运维」账号被授权的项目码集合（{@code Set<String>}·人类 USER 会话时由 CallerScopeWebFilter 从 Sa-Token 会话写入·
     *  控制器据此做行级项目过滤/越权校验·空=超管或平台令牌不限）。与 {@link #CALLER_PROJECT_ATTR}（单项目 M2M 令牌）互补。 */
    public static final String CALLER_PROJECTS_ATTR = "nexusai.caller.projects";

    /** TraceId 请求/响应头（全链路串联·observe TraceIdWebFilter 写入并回写响应） */
    public static final String TRACE_HEADER = "X-Trace-Id";
    /** TraceId 透传属性键（exchange attribute·与 {@link #CTX_ATTR} 同源收口于 common 单一真理源，消除 observe↔relay 隐式键约定） */
    public static final String TRACE_ATTR = "nexusai.traceId";

    /** UsageEvent 用量回传 Redis Stream key */
    public static final String USAGE_STREAM_KEY = "nexusai:usage:stream";
    /** Token 同步/吊销跨实例广播 topic（吊销须即时清各节点缓存·不靠 60s TTL 兜底） */
    public static final String TOKEN_SYNC_TOPIC = "nexusai:token:sync";
    /** 控制面项目 M2M 令牌增删改/轮转跨实例广播 topic（吊销须即时各节点重载·控制台令牌无 TTL 兜底） */
    public static final String PROJECT_TOKEN_SYNC_TOPIC = "nexusai:projecttoken:sync";
    /** 配置（渠道/路由/模型/渠道密钥）变更跨实例广播 topic（须即时清各节点路由/别名/价格缓存·不靠 300s TTL 兜底） */
    public static final String CONFIG_SYNC_TOPIC = "nexusai:config:sync";

    /** 请求日志 Redis Stream key（网关最大表·日志管线缓冲；§11.1） */
    public static final String REQUEST_LOG_STREAM_KEY = "nexusai:requestlog:stream";
    /** 请求日志消费组 */
    public static final String REQUEST_LOG_GROUP = "nexusai-requestlog";

    /** 请求 ID 回写头（B2B 对账：客户侧持此 ID 调 /v1/generation 回查 usage/cost） */
    public static final String REQUEST_ID_HEADER = "X-Nexusai-Request-Id";
    /** 本次请求消耗 USD 响应头（结算后回写·USD 平铺串如 0.001234·流式走 trailers 不可用时省略） */
    public static final String COST_USD_HEADER = "X-Nexusai-Cost-USD";
    /** 结算后余额 USD 响应头（项目池/钱包视角·unlimited 回 "unlimited"） */
    public static final String BALANCE_USD_HEADER = "X-Nexusai-Balance-USD";
    /** 响应缓存命中标记头 */
    public static final String CACHE_HIT_HEADER = "X-Nexusai-Cache";
    /** 显式幂等键请求头（IdempotencyFilter） */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** OpenAI 强制带 usage（流式必须回 usage·否则 token 计量被旁路·计费失真） */
    public static final String STREAM_OPTIONS = "stream_options";
    public static final String INCLUDE_USAGE = "include_usage";
}
