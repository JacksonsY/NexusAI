package com.nexusai.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.persistence.po.NaiToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

/**
 * JWT 入站认证（HS256·业界"短期令牌取代静态 key"趋势）：客户端持短期 JWT 而非裸 sk-，
 * 经配置密钥 HMAC-SHA256 验签 + exp 校验后，从约定 claim（默认 {@code key}）取出 sk- 委托 {@link ApiKeyAuthService}
 * （复用全部 Key 元数据/额度/限流/白名单·JWT 仅是凭证信封）。默认关（{@code nexusai.auth.jwt.enabled=false}）。
 *
 * <p>手写 HS256 验签（零新依赖·{@code javax.crypto.Mac}）：足够 M2M 短期令牌场景；需 RS256/JWKS（对接企业 IdP）再扩。
 *
 * @author nexusai
 */
@Slf4j
@Component
public class JwtAuthenticationProvider implements AuthenticationProvider {

    private final ApiKeyAuthService authService;
    private final boolean enabled;
    private final byte[] secret;
    private final String keyClaim;

    public JwtAuthenticationProvider(ApiKeyAuthService authService,
                                     @Value("${nexusai.auth.jwt.enabled:false}") boolean enabled,
                                     @Value("${nexusai.auth.jwt.secret:}") String secret,
                                     @Value("${nexusai.auth.jwt.key-claim:key}") String keyClaim) {
        this.authService = authService;
        boolean hasSecret = secret != null && !secret.isBlank();
        this.enabled = enabled && hasSecret;
        this.secret = hasSecret ? secret.getBytes(StandardCharsets.UTF_8) : new byte[0];
        this.keyClaim = keyClaim;
        if (enabled && !hasSecret) {
            log.warn("nexusai.auth.jwt.enabled=true 但未配 nexusai.auth.jwt.secret → JWT 认证不生效");
        }
    }

    @Override
    public boolean supports(String authHeader) {
        if (!enabled || authHeader == null || !authHeader.startsWith(GatewayConstants.BEARER_PREFIX)) {
            return false;
        }
        String t = authHeader.substring(GatewayConstants.BEARER_PREFIX.length()).trim();
        // JWT 形态：非 sk- 前缀 + 三段点分（header.payload.sig）
        return !t.startsWith(GatewayConstants.API_KEY_PREFIX) && t.chars().filter(c -> c == '.').count() == 2;
    }

    @Override
    public NaiToken authenticate(String authHeader) {
        String jwt = authHeader.substring(GatewayConstants.BEARER_PREFIX.length()).trim();
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            throw GatewayException.of(GatewayErrorCode.UNAUTHORIZED, "JWT 格式错误");
        }
        // 0. 头部 alg 校验：仅接受 HS256，拒绝 none / 算法混淆（alg confusion）攻击
        assertAlgHs256(parts[0]);
        // 1. HS256 验签（常量时间比对）
        byte[] expected = hmacSha256((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(parts[2]);
        } catch (Exception e) {
            throw GatewayException.of(GatewayErrorCode.UNAUTHORIZED, "JWT 签名解码失败");
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw GatewayException.of(GatewayErrorCode.UNAUTHORIZED, "JWT 签名校验失败");
        }
        // 2. 解析 payload + exp
        Map<String, Object> claims;
        try {
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            claims = JsonUtils.parse(payloadJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw GatewayException.of(GatewayErrorCode.UNAUTHORIZED, "JWT 载荷解析失败");
        }
        if (claims == null) {
            throw GatewayException.of(GatewayErrorCode.UNAUTHORIZED, "JWT 载荷为空");
        }
        // exp 必须存在且为数值：缺失/非法（如被篡改为字符串）一律拒绝，杜绝"无过期 = 永久有效"绕过
        Object exp = claims.get("exp");
        if (!(exp instanceof Number n)) {
            throw GatewayException.of(GatewayErrorCode.UNAUTHORIZED, "JWT 缺少有效 exp");
        }
        if (n.longValue() * 1000L < System.currentTimeMillis()) {
            throw GatewayException.of(GatewayErrorCode.UNAUTHORIZED, "JWT 已过期");
        }
        // 3. 取 key claim → 委托 sk- 认证（复用全部 Key 策略）
        Object sk = claims.get(keyClaim);
        if (sk == null || sk.toString().isBlank()) {
            throw GatewayException.of(GatewayErrorCode.UNAUTHORIZED, "JWT 缺少 " + keyClaim + " claim");
        }
        return authService.authenticate(GatewayConstants.BEARER_PREFIX + sk);
    }

    @Override
    public int order() {
        return 50;   // 早于 API Key 兜底
    }

    /** 校验 JWT 头部 {@code alg} 为 HS256，拒绝 none 及非对称算法混淆。 */
    private void assertAlgHs256(String headerSegment) {
        String alg;
        try {
            String headerJson = new String(Base64.getUrlDecoder().decode(headerSegment), StandardCharsets.UTF_8);
            Map<String, Object> header = JsonUtils.parse(headerJson, new TypeReference<Map<String, Object>>() {
            });
            alg = header == null ? null : String.valueOf(header.get("alg"));
        } catch (Exception e) {
            throw GatewayException.of(GatewayErrorCode.UNAUTHORIZED, "JWT 头部解析失败");
        }
        if (!"HS256".equals(alg)) {
            throw GatewayException.of(GatewayErrorCode.UNAUTHORIZED, "JWT 不支持的签名算法: " + alg);
        }
    }

    private byte[] hmacSha256(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw GatewayException.of(GatewayErrorCode.UNAUTHORIZED, "JWT 验签异常");
        }
    }
}
