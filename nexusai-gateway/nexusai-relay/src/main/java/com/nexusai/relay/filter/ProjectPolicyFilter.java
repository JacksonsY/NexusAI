package com.nexusai.relay.filter;

import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.relay.RelayFormats;
import com.nexusai.relay.context.RelayContext;
import com.nexusai.relay.security.ProjectPolicy;
import com.nexusai.relay.security.ProjectPolicyCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/**
 * ③′ 项目访问策略闸门（置于 Key 级模型权限之后）：
 * 按 sk- Key 归属项目（{@code nai_token.project_code}）执行项目级约束——
 * <b>模型白名单</b>/<b>能力白名单</b>越权即拒（403）；<b>渠道约束</b>解析后挂上下文，
 * 由路由阶段执行（route 出的渠道不满足 → 排除重选，见 RelayOrchestrator）。
 * <p>与 Key 级 {@code model_limits} 是「∩」关系：Key 白名单 ⊆ 项目白名单才放行（两道闸各自独立判）。
 *
 * @author nexusai
 */
@Component
@RequiredArgsConstructor
public class ProjectPolicyFilter implements RelayFilter {

    private final ProjectPolicyCache policyCache;

    @Override
    public void filter(RelayContext ctx, ServerWebExchange exchange) {
        ProjectPolicy policy = policyCache.byCode(ctx.getToken().getProjectCode());
        if (policy.unrestricted()) {
            return;
        }
        String model = ctx.getPeek().model();
        if (!policy.modelAllowed(model)) {
            throw GatewayException.of(GatewayErrorCode.MODEL_NOT_PERMITTED,
                    "所属项目无权访问模型: " + model);
        }
        // 模态判定必须与计费/结算同源（RelayFormats.modalityOf）：私有副本曾遗漏 /audio/ 与 embedContent，
        // 致音频/Gemini 向量请求被判为 chat 而绕过项目能力白名单（越权）。
        String modality = RelayFormats.modalityOf(ctx.getPath());
        if (!policy.modalityAllowed(modality)) {
            throw GatewayException.of(GatewayErrorCode.MODEL_NOT_PERMITTED,
                    "所属项目无权调用能力: " + modality);
        }
        // 渠道约束交给路由阶段（候选渠道不满足策略 → 排除换选）
        ctx.setProjectPolicy(policy);
    }

    @Override
    public int getOrder() {
        return ORDER_MODEL_PERMISSION + 5;   // Key 级模型权限之后·限流之前
    }
}
