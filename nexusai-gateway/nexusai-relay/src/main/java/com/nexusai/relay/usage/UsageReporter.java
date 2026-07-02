package com.nexusai.relay.usage;

import com.nexusai.billing.event.UsageEventPublisher;
import com.nexusai.billing.money.UsdAmount;
import com.nexusai.contract.usage.UsageEvent;
import com.nexusai.runtime.RuntimeSettings;
import com.nexusai.observe.log.RequestBodyCapture;
import com.nexusai.observe.log.RequestLogPublisher;
import com.nexusai.persistence.mapper.NaiUsageLogMapper;
import com.nexusai.persistence.po.NaiUsageLog;
import com.nexusai.relay.RelayFormats;
import com.nexusai.relay.context.RelayContext;
import com.nexusai.router.affinity.StickyAffinity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import com.nexusai.common.util.ClientFamilyDetector;

/**
 * 计量遥测上报（结算后扇出·一次规范化→多路扇出）：
 * ① {@code nai_usage_log} 同步落库（/v1/generation 对账数据源）② UsageEvent（Redis Stream→业务面）
 * ③请求日志管线（Redis Stream→nai_request_log·默认只存 prompt sha256）④会话亲和条目元数据补写。
 * 全部 fire-and-forget：单路失败只记日志，绝不影响结算主链与回写。
 *
 * @author nexusai
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UsageReporter {

    private final NaiUsageLogMapper usageLogMapper;
    private final UsageEventPublisher usageEventPublisher;
    private final RequestLogPublisher requestLogPublisher;
    private final RequestBodyCapture bodyCapture;
    private final RuntimeSettings runtimeSettings;
    private final StickyAffinity stickyAffinity;

    /** User-Agent 入库截断上限（字符数·防超长头撑爆日志列）。 */
    private static final int USER_AGENT_MAX_CHARS = 512;
    /** 上游状态缺失时的默认 HTTP 状态码（请求日志占位）。 */
    private static final int DEFAULT_HTTP_STATUS = 200;
    /** 正文捕获大小配置（KB）→ 字节换算因子。 */
    private static final int KB = 1024;
    /** 纳秒→毫秒换算因子。 */
    private static final long NANOS_PER_MILLI = 1_000_000L;

    /** 用量审计行（同步落库·status 恒成功语义由调用方保证只在结算后调·usdMicro=micro-USD 入账当量）。 */
    public void writeUsageLog(RelayContext ctx, Usage usage, int totalTokens, long usdMicro) {
        try {
            NaiUsageLog logPo = new NaiUsageLog();
            logPo.setRequestId(ctx.getRequestId());
            logPo.setProjectCode(ctx.getToken().getProjectCode());
            logPo.setWorkspaceId(ctx.getToken().getWorkspaceId());   // 三层身份链：B2C 账单按工作区聚合（B2B 为空）
            logPo.setChannelId(ctx.getRoute().channel().getId());
            logPo.setKeyId(ctx.getRoute().key().getId());
            logPo.setModel(ctx.getPeek().model());
            logPo.setPromptTokens(usage.promptTokens());
            logPo.setCompletionTokens(usage.completionTokens());
            logPo.setCacheTokens(usage.cachedTokens());
            logPo.setCacheCreationTokens(usage.cacheCreationTokens());
            logPo.setTotalTokens(totalTokens);
            logPo.setCostUsd(UsdAmount.fromMicros(usdMicro));
            // 🔴 cost_basis（上游成本）网关侧无独立成本价来源，显式置 = cost_usd（毛利 0），由业务面用自有渠道成本对账修正；
            // 此前从不赋值 → DB 默认 0 被误读为「100% 毛利」。显式 cost==sell 是诚实占位，杜绝该假象。
            logPo.setCostBasis(UsdAmount.fromMicros(usdMicro));
            logPo.setStatus(1);
            usageLogMapper.insert(logPo);
        } catch (Exception e) {
            log.error("nai_usage_log 写入失败 requestId={}", ctx.getRequestId(), e);
        }
    }

    /** UsageEvent（网关→业务面·Redis Stream·强类型 record·wire 序列化在 publisher 的 Stream 边界做）。 */
    public void publishUsageEvent(RelayContext ctx, Usage usage, int totalTokens, long usdMicro, String usageSource) {
        UsageEvent event = new UsageEvent(
                ctx.getRequestId(),
                ctx.getToken().getProjectCode(),                 // 项目维度归集
                ctx.getToken().getWorkspaceId(),                 // 三层身份链：B2C 工作区（B2B 为空）
                ctx.getPeek().model(),
                ctx.getRoute().channel().getId(),
                ctx.getRoute().key().getId(),
                usage.promptTokens(),
                usage.completionTokens(),
                usage.cachedTokens(),
                usage.cacheCreationTokens(),
                totalTokens,
                UsdAmount.fromMicros(usdMicro),
                usageSource);                                    // parsed|estimated|image-fallback|failed（审计/告警 usage 丢失率）
        usageEventPublisher.publish(event);
    }

    /** 请求日志（与 UsageEvent 同源分流·走日志管线 Redis Stream→nai_request_log）。默认只存请求体 sha256。 */
    public void publishRequestLog(RelayContext ctx, Usage usage, int totalTokens, long usdMicro, String usageSource) {
        try {
            int status = ctx.getUpstreamStatus() != null && ctx.getUpstreamStatus() >= 400 ? 0 : 1;
            String modality = RelayFormats.modalityOf(ctx.getPath());
            Map<String, String> e = new HashMap<>();
            e.put("requestId", ctx.getRequestId());
            e.put("traceId", nullSafe(ctx.getTraceId()));   // 全链路 trace_id → nai_request_log.trace_id（供控制台 traceId 串查）
            e.put("projectCode", nullSafe(ctx.getToken().getProjectCode()));
            e.put("workspaceId", ctx.getToken().getWorkspaceId() == null ? "" : String.valueOf(ctx.getToken().getWorkspaceId()));
            e.put("channelId", String.valueOf(ctx.getRoute().channel().getId()));
            e.put("keyId", String.valueOf(ctx.getRoute().key().getId()));
            e.put("model", nullSafe(ctx.getPeek().model()));
            e.put("targetModel", nullSafe(ctx.getRoute().targetModel()));
            e.put("modality", modality);
            e.put("streamMode", String.valueOf(ctx.getPeek().stream()));
            e.put("apiFormat", RelayFormats.apiFormatOf(ctx.getPath(), modality));
            e.put("endpointApiFormat", RelayFormats.endpointApiFormatOf(ctx.getRoute().channel().getProtocol(), modality));
            e.put("retryCount", String.valueOf(ctx.getRetryCount()));
            e.put("fallbackCount", String.valueOf(ctx.getFallbackCount()));
            e.put("ttftMs", ctx.getTtftMs() == null ? "" : String.valueOf(ctx.getTtftMs()));
            e.put("userAgent", truncate(nullSafe(ctx.getUserAgent()), USER_AGENT_MAX_CHARS));
            e.put("clientFamily", nullSafe(ClientFamilyDetector.detect(ctx.getUserAgent())));
            e.put("promptTokens", String.valueOf(usage.promptTokens()));
            e.put("completionTokens", String.valueOf(usage.completionTokens()));
            e.put("cacheTokens", String.valueOf(usage.cachedTokens()));   // 键名对齐 RequestLogConsumer
            e.put("cacheCreationTokens", String.valueOf(usage.cacheCreationTokens()));
            e.put("totalTokens", String.valueOf(totalTokens));
            e.put("costUsd", UsdAmount.format(usdMicro));
            e.put("usageSource", usageSource);
            e.put("status", String.valueOf(status));
            e.put("httpStatus", String.valueOf(ctx.getUpstreamStatus() == null ? DEFAULT_HTTP_STATUS : ctx.getUpstreamStatus()));
            e.put("durationMs", String.valueOf((System.nanoTime() - ctx.getStartNanos()) / NANOS_PER_MILLI));
            e.put("clientIp", nullSafe(ctx.getClientIp()));
            e.put("promptHash", sha256Hex(ctx.getBody()));
            requestLogPublisher.publish(e);
            // 正文捕获（数据面集成点）：store=rdb 且系统设置 request_record_level=full 时双落正文分离库
            if ("full".equalsIgnoreCase(runtimeSettings.getString("request_record_level", "full"))) {
                int maxReq = runtimeSettings.getInt("max_request_body_kb", KB) * KB;
                int maxResp = runtimeSettings.getInt("max_response_body_kb", KB) * KB;
                bodyCapture.capture(ctx.getRequestId(),
                        truncateBytes(ctx.getBody(), maxReq),
                        truncateBytes(ctx.getResponseCapture(), maxResp));
            }
        } catch (Exception ex) {
            log.warn("请求日志构建失败 requestId={}: {}", ctx.getRequestId(), ex.getMessage());
        }
    }

    /** 把展示元数据补写进会话亲和条目（监控亲和列表·meta 成败皆真实）+ 缓存命中 tokens 仅成功累计。 */
    public void attachAffinityMeta(RelayContext ctx, boolean success, String modality, Usage usage) {
        if (ctx.getRoute() == null || ctx.getAffinityKey() == null) {
            return;
        }
        try {
            stickyAffinity.attachMeta(ctx.getRoute().channel().getId(), ctx.getAffinityKey(),
                    ctx.getToken().getProjectCode(), ctx.getPeek().model(),
                    RelayFormats.apiFormatOf(ctx.getPath(), modality),
                    ClientFamilyDetector.detect(ctx.getUserAgent()),
                    ctx.getAffinitySource(), !success || usage == null ? 0 : usage.cachedTokens());
        } catch (Exception e) {
            log.debug("亲和元数据补写失败（不影响主链）requestId={}", ctx.getRequestId(), e);
        }
    }

    private static byte[] truncateBytes(byte[] data, int max) {
        if (data == null || data.length <= max || max <= 0) {
            return data;
        }
        return Arrays.copyOf(data, max);
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String sha256Hex(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            return "";
        }
    }
}
