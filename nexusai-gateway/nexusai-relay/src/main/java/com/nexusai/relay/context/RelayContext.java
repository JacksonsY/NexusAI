package com.nexusai.relay.context;

import com.nexusai.billing.config.UsdRate;
import com.nexusai.common.enums.ApiFormat;
import com.nexusai.persistence.po.NaiToken;
import com.nexusai.protocol.passthrough.ChatRequestPeek;
import com.nexusai.router.RouteResult;
import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.atomic.AtomicBoolean;
import com.nexusai.relay.security.ProjectPolicy;

/**
 * 单次转发上下文（贯穿管线：鉴权→路由→计量）。
 * <p>含 byte[] 请求/响应缓冲与 AtomicBoolean 结算闸——身份语义敏感，故仅生成
 * getter/setter，不生成 equals/hashCode/toString（避免对二进制体与原子状态做值比较）。
 *
 * @author nexusai
 */
@Getter
@Setter
public class RelayContext {

    /** 该模型的结构化 USD 价格（来自 nai_model），流末按真实 usage 结算用 */
    private UsdRate usdRate;

    private String requestId;
    /** 全链路 trace_id（TraceIdWebFilter 生成·gate 从 TRACE_ATTR 取入·随请求日志落库·供控制台按 traceId 串查） */
    private String traceId;
    private String path;
    private String clientIp;
    /** 客户端 User-Agent（gate 采集·UA→client_family 推导 + 请求日志） */
    private String userAgent;
    /** 会话亲和键（AffinityKeyResolver 解析一次缓存·route/settle 复用） */
    private String affinityKey;
    /** 亲和键来源：metadata / digest / token（监控展示） */
    private String affinitySource;

    /** 鉴权结果 */
    private NaiToken token;
    private String group;

    /** 项目访问策略（ProjectPolicyFilter 注入·null=无约束；路由阶段据此排除不允许的渠道） */
    private ProjectPolicy projectPolicy;

    /** 最小解析 */
    private ChatRequestPeek peek;
    /** 原始（可能已强制 include_usage）请求体 */
    private byte[] body;
    /** 入站 Content-Type（gate 采集·multipart 音频/图像编辑判定 + 原样透传上游 boundary） */
    private String contentType;

    /** multipart/form-data 入站（音频转写/翻译、图像编辑·文件上传）：原体二进制透传，禁 JSON 改写。 */
    public boolean isMultipart() {
        return contentType != null && contentType.toLowerCase().startsWith("multipart/");
    }

    /** 响应正文捕获缓冲（store=rdb 且 collect 开启时由 writeBack 聚合·上限 5MB·全量采集档） */
    private byte[] responseCapture;

    /** 两级路由结果 */
    private RouteResult route;

    /** 强闸门预扣的 micro-USD（流末多退少补依据） */
    private long frozenUsdAmount;

    /** 图片张数（image 模态·gate 解析请求体 n 字段一次·freeze/settle 同源复用·默认 1） */
    private int imageCount = 1;

    /** 入站 Key 并发槽令牌（InboundConcurrencyFilter 占用·请求终态由编排器释放·null=未占/不限并发） */
    private String inboundSlotToken;

    /** 上游返回 HTTP 状态码（writeBack 回填，供请求日志） */
    private Integer upstreamStatus;

    /** 首字节耗时（ms·writeBack 首块回填·流式即 TTFT·供请求日志/活跃注册表） */
    private volatile Long ttftMs;

    /** 同渠道换 Key 重试次数（故障转移链路累计·请求日志 has_retry 数据源） */
    private int retryCount;
    /** 跨渠道故障转移次数（请求日志 has_fallback 数据源） */
    private int fallbackCount;

    /** P4 Canonical 跨协议互转（渠道 setting.canonical=true 且入站≠上游格式·非流式时启用） */
    private boolean canonicalConvert;
    private ApiFormat inboundFormat;
    private ApiFormat upstreamFormat;

    /** 管线提前终止标记（鉴权/路由/闸门任一拒绝即置位，orchestrator 不再转发上游） */
    private volatile boolean terminal;

    /** 流式客户端中断标记（评审 H1：客户端断开时只按已采 prompt 结算，不补扣未产出 completion） */
    private volatile boolean streamAborted;

    /** 显式 Idempotency-Key 对应的 Redis key（仅客户端带头时设置；自动 body 去重不走终态状态机）。 */
    private String idempotencyRedisKey;
    /** 显式幂等 key 保留 TTL，用于成功后把 IN_FLIGHT 切为 DONE。 */
    private long idempotencyTtlSeconds;

    private long startNanos;

    /** 结算抢占闸（评审 H2：writeBack 与 doFinally 竞争时，确保多退少补只执行一次）。 */
    private final AtomicBoolean settleGuard = new AtomicBoolean(false);

    /** 抢占结算（首个调用返回 true，其余 false→跳过）。 */
    public boolean tryStartSettle() {
        return settleGuard.compareAndSet(false, true);
    }
}
