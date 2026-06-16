package com.nexusai.relay.filter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.nexusai.common.enums.ApiFormat;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.common.util.IpMatcher;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.relay.RelayFormats;
import com.nexusai.relay.context.RelayContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * ③ Token 访问限制（独立密钥 standalone key）：校验本 Key 的协议入口白名单
 * （{@code nai_token.allow_api_formats}）与来源 IP 白名单（{@code nai_token.allow_ips}）。
 * 白名单为空/未配 = 不限制（放行全部）；越权即拒（403）。JSON 解析失败 <b>fail-open</b>
 * （warn 后放行·与 {@link ModelPermissionFilter} model_limits 口径一致）。
 * <p>模型白名单 / 限流分别在 {@link ModelPermissionFilter} / {@link RateLimitFilter}；
 * 渠道白名单（allow_providers）需 route 后上下文，留后续在 route 后的闸门处理。
 *
 * @author nexusai
 */
@Slf4j
@Component
public class TokenAccessFilter implements RelayFilter {

    @Override
    public void filter(RelayContext ctx, ServerWebExchange exchange) {
        checkApiFormat(ctx);
        checkClientIp(ctx);
    }

    private void checkApiFormat(RelayContext ctx) {
        String json = ctx.getToken().getAllowApiFormats();
        if (json == null || json.isBlank()) {
            return;
        }
        List<String> allowed = parseJsonArray(json, ctx);
        if (allowed == null || allowed.isEmpty()) {
            return;
        }
        // 🔴 inboundFormat 在装配阶段(order≥60)才落 ctx，本闸门(ORDER_TOKEN_ACCESS=35)执行时恒为 null，
        // 旧实现 `ctx.getInboundFormat()==null→return` 等于白名单从不生效（被限单协议的 Key 可调用任意协议入口）。
        // 直接由不可变请求路径纯函数派生，与装配阶段同口径。
        ApiFormat inbound = RelayFormats.inboundFormat(ctx.getPath());
        String fmt = inbound.name().toLowerCase(Locale.ROOT);
        boolean ok = allowed.stream().anyMatch(a -> a != null && a.trim().equalsIgnoreCase(fmt));
        if (!ok) {
            throw GatewayException.of(GatewayErrorCode.API_FORMAT_NOT_PERMITTED, "该 Key 无权使用协议入口: " + fmt);
        }
    }

    private void checkClientIp(RelayContext ctx) {
        String raw = ctx.getToken().getAllowIps();
        if (raw == null || raw.isBlank()) {
            return;
        }
        List<String> allowed = parseIps(raw, ctx);
        if (allowed == null || allowed.isEmpty()) {
            return;
        }
        String ip = ctx.getClientIp();
        if (ip == null || ip.isBlank()) {
            return;
        }
        if (!IpMatcher.inWhitelist(ip, allowed)) {
            throw GatewayException.of(GatewayErrorCode.IP_NOT_ALLOWED, "该 Key 不允许来源 IP: " + ip);
        }
    }

    /** allow_ips 兼容 JSON 数组与逗号分隔两种存法。 */
    private List<String> parseIps(String raw, RelayContext ctx) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("[")) {
            return parseJsonArray(trimmed, ctx);
        }
        return Arrays.stream(trimmed.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private List<String> parseJsonArray(String json, RelayContext ctx) {
        try {
            return JsonUtils.parse(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("token 访问白名单解析失败 tokenId={}，放行（fail-open）: {}", ctx.getToken().getId(), json);
            return null;
        }
    }

    @Override
    public int getOrder() {
        return ORDER_TOKEN_ACCESS;
    }
}
