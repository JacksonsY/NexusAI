package com.nexusai.relay.filter;

import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.limit.RateLimiterService;
import com.nexusai.persistence.po.NaiToken;
import com.nexusai.relay.context.RelayContext;
import com.nexusai.relay.security.ProjectPolicy;
import com.nexusai.relay.security.ProjectPolicyCache;
import com.nexusai.relay.security.WorkspaceLimitsCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/**
 * ④ 三层限流（Key / 项目 / 工作区·RPM 硬闸门 + TPM 软闸门；Redis 服务端时钟防多实例漂移）：
 * <ul>
 *   <li><b>Key 级</b>：nai_token.rpm_limit/tpm_limit（最细粒度）；</li>
 *   <li><b>项目级</b>：nai_project.rpm_limit/tpm_limit（项目下全部 Key 共享·防单项目打爆上游·{@link ProjectPolicyCache}）；</li>
 *   <li><b>工作区级</b>：nai_workspace.rpm_limit/tpm_limit（B2C 散户级·token 挂 workspace 时·{@link WorkspaceLimitsCache}）。</li>
 * </ul>
 * 各级 0/NULL=不限。TPM 实际消耗由 doSettle 响应后按同维度 key 累加（SettlementService）。
 *
 * @author nexusai
 */
@Component
@RequiredArgsConstructor
public class RateLimitFilter implements RelayFilter {

    private final RateLimiterService rateLimiter;
    private final ProjectPolicyCache projectPolicyCache;
    private final WorkspaceLimitsCache workspaceLimitsCache;

    @Override
    public void filter(RelayContext ctx, ServerWebExchange exchange) {
        NaiToken token = ctx.getToken();

        // ① Key 级
        Long tokenId = token.getId();
        int keyRpm = token.getRpmLimit() == null ? 0 : token.getRpmLimit();
        int keyTpm = token.getTpmLimit() == null ? 0 : token.getTpmLimit();
        if (!rateLimiter.tryAcquire("apikey", String.valueOf(tokenId), keyRpm)) {
            throw GatewayException.of(GatewayErrorCode.RATE_LIMITED, "Key RPM 超限");
        }
        if (!rateLimiter.tpmAllowed("token:" + tokenId, keyTpm)) {
            throw GatewayException.of(GatewayErrorCode.RATE_LIMITED, "Key TPM 超限");
        }

        // ② 项目级总闸（项目下全部 Key 共享）
        String projectCode = token.getProjectCode();
        ProjectPolicy policy = projectPolicyCache.byCode(projectCode);
        if (policy.rpmLimit() > 0 && !rateLimiter.tryAcquire("project", projectCode, policy.rpmLimit())) {
            throw GatewayException.of(GatewayErrorCode.RATE_LIMITED, "项目 RPM 超限");
        }
        if (!rateLimiter.tpmAllowed("project:" + projectCode, policy.tpmLimit())) {
            throw GatewayException.of(GatewayErrorCode.RATE_LIMITED, "项目 TPM 超限");
        }

        // ③ 工作区级（B2C 散户·token 挂 workspace 时）
        Long workspaceId = token.getWorkspaceId();
        if (workspaceId != null) {
            WorkspaceLimitsCache.Limits wl = workspaceLimitsCache.byId(workspaceId);
            if (wl.rpm() > 0 && !rateLimiter.tryAcquire("workspace", String.valueOf(workspaceId), wl.rpm())) {
                throw GatewayException.of(GatewayErrorCode.RATE_LIMITED, "工作区 RPM 超限");
            }
            if (!rateLimiter.tpmAllowed("workspace:" + workspaceId, wl.tpm())) {
                throw GatewayException.of(GatewayErrorCode.RATE_LIMITED, "工作区 TPM 超限");
            }
        }
    }

    @Override
    public int getOrder() {
        return ORDER_RATE_LIMIT;
    }
}
