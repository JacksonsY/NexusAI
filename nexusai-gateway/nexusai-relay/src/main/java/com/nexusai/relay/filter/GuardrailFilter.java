package com.nexusai.relay.filter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.persistence.po.NaiToken;
import com.nexusai.relay.context.RelayContext;
import com.nexusai.relay.guard.GuardResult;
import com.nexusai.relay.guard.Guardrail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 内容安全闸门（缺口分析 C 类）：在 peek 后、路由/预扣前按 {@link Guardrail#priority()} 顺序跑护栏，任一拦截即抛
 * {@link GatewayErrorCode#CONTENT_BLOCKED}（前置失败不扣 USD·无需回退）。
 * <p><b>两档启用</b>（per-key 绑定）：
 * <ul>
 *   <li>全局 {@code nexusai.guardrail.enabled=true} → 对<b>所有</b> Key 执行；</li>
 *   <li>否则按 Key 级 {@code nai_token.feature_settings} JSON 的 {@code content_guardrail}（true 或 {enabled:true}）
 *       → 仅该 Key 执行（不同客户挂不同策略）。</li>
 * </ul>
 * 始终装配（无护栏实现或两档都未开 → 空操作）。
 *
 * @author nexusai
 */
@Slf4j
@Component
public class GuardrailFilter implements RelayFilter {

    private final List<Guardrail> guardrails;
    private final boolean globalEnabled;

    public GuardrailFilter(List<Guardrail> guardrails,
                           @Value("${nexusai.guardrail.enabled:false}") boolean globalEnabled) {
        this.guardrails = guardrails.stream().sorted(Comparator.comparingInt(Guardrail::priority)).toList();
        this.globalEnabled = globalEnabled;
    }

    @Override
    public int getOrder() {
        return ORDER_GUARDRAIL;   // 27·别名归一(25)之后(护栏看归一后模型名)、模型权限(30)之前·独立顺位防与别名同序
    }

    @Override
    public void filter(RelayContext ctx, ServerWebExchange exchange) {
        if (guardrails.isEmpty() || ctx.getBody() == null || ctx.getBody().length == 0) {
            return;
        }
        if (!globalEnabled && !keyOptsIn(ctx.getToken())) {
            return;   // 全局关 且 本 Key 未绑定 → 跳过
        }
        String prompt = new String(ctx.getBody(), StandardCharsets.UTF_8);
        for (Guardrail g : guardrails) {
            GuardResult r = g.check(prompt, ctx);
            if (!r.allowed()) {
                log.info("guardrail 拦截 requestId={} by={} reason={}", ctx.getRequestId(), g.name(), r.reason());
                throw GatewayException.of(GatewayErrorCode.CONTENT_BLOCKED, "内容安全拦截：" + r.reason());
            }
        }
    }

    /** Key 级护栏开关：feature_settings.content_guardrail = true 或 {enabled:true}。解析失败=未开。 */
    private boolean keyOptsIn(NaiToken token) {
        if (token == null || token.getFeatureSettings() == null || token.getFeatureSettings().isBlank()) {
            return false;
        }
        try {
            Map<String, Object> fs = JsonUtils.parse(token.getFeatureSettings(), new TypeReference<Map<String, Object>>() {
            });
            Object g = fs == null ? null : fs.get("content_guardrail");
            if (g instanceof Boolean b) {
                return b;
            }
            if (g instanceof Map<?, ?> m) {
                return Boolean.TRUE.equals(m.get("enabled"));
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
