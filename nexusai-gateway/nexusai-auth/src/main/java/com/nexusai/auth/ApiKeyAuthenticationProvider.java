package com.nexusai.auth;

import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.persistence.po.NaiToken;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * sk- API Key 认证（默认 + 兜底）：{@code Bearer sk-xxx} → SHA-256 → {@link ApiKeyAuthService} 查表。
 * supports 命中任意 Bearer（最低优先级·放在 JWT 等特定 provider 之后兜底）。
 *
 * @author nexusai
 */
@Component
@RequiredArgsConstructor
public class ApiKeyAuthenticationProvider implements AuthenticationProvider {

    private final ApiKeyAuthService authService;

    @Override
    public boolean supports(String authHeader) {
        return authHeader != null && authHeader.startsWith(GatewayConstants.BEARER_PREFIX);
    }

    @Override
    public NaiToken authenticate(String authHeader) {
        return authService.authenticate(authHeader);
    }

    @Override
    public int order() {
        return 100;   // 兜底（JWT 等特定形态 provider 先判）
    }
}
