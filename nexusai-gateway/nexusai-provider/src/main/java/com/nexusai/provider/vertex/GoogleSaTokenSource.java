package com.nexusai.provider.vertex;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.common.util.JsonUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GCP 服务账号 → OAuth2 access token（JWT-bearer 流：RS256 自签 JWT → token_uri 换 access_token），
 * 按 client_email 缓存至过期前 60s（同步阻塞 HTTP·在 boundedElastic 调用）。供 Vertex AI 鉴权。
 *
 * @author nexusai
 */
public final class GoogleSaTokenSource {

    private GoogleSaTokenSource() {
    }

    private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ConcurrentHashMap<String, Cached> CACHE = new ConcurrentHashMap<>();
    /** 每服务账号一把锁：刷新 token 的阻塞 HTTP 只串行化「同一 SA」，不再全局串行（不同 SA 并发刷新互不阻塞）。 */
    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();

    private record Cached(String token, long expiresAtMs) {
    }

    /** 取（缓存的）access token；saJson 为完整服务账号 JSON。 */
    public static String accessToken(String saJson) {
        JsonNode sa = JsonUtils.readTree(saJson.getBytes(StandardCharsets.UTF_8));
        String clientEmail = sa.path("client_email").asText();
        long now = System.currentTimeMillis();
        Cached c = CACHE.get(clientEmail);
        if (c != null && c.expiresAtMs() > now + 60_000L) {
            return c.token();
        }
        // 🔴 只锁「当前 SA」：阻塞 HTTP 换取在 boundedElastic 上跑，但旧实现的全局 synchronized(CACHE) 会把所有 SA 的
        // 刷新串行化（一个慢 Google 往返堵住全部 Vertex 调用）。改为 per-clientEmail 锁，跨 SA 并发刷新。
        Object lock = LOCKS.computeIfAbsent(clientEmail, k -> new Object());
        synchronized (lock) {
            c = CACHE.get(clientEmail);
            if (c != null && c.expiresAtMs() > now + 60_000L) {
                return c.token();
            }
            String tokenUri = sa.path("token_uri").asText("https://oauth2.googleapis.com/token");
            String jwt = buildSignedJwt(clientEmail, tokenUri, sa.path("private_key").asText(), now);
            Cached fresh = exchange(tokenUri, jwt, now);
            CACHE.put(clientEmail, fresh);
            return fresh.token();
        }
    }

    private static String buildSignedJwt(String iss, String aud, String pkPem, long now) {
        long iat = now / 1000;
        long exp = iat + 3600;
        String header = b64u("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String claims = b64u(("{\"iss\":\"" + iss + "\",\"scope\":\"" + SCOPE + "\",\"aud\":\"" + aud
                + "\",\"iat\":" + iat + ",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + claims;
        byte[] sig = rs256(signingInput.getBytes(StandardCharsets.UTF_8), pkPem);
        return signingInput + "." + b64u(sig);
    }

    private static Cached exchange(String tokenUri, String jwt, long now) {
        try {
            String form = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=" + jwt;
            HttpRequest req = HttpRequest.newBuilder(URI.create(tokenUri))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode j = JsonUtils.readTree(resp.body());
            String token = j.path("access_token").asText(null);
            if (token == null) {
                throw new IllegalStateException("Vertex SA token 换取失败: " + resp.body());
            }
            long expiresIn = j.path("expires_in").asLong(3600);
            return new Cached(token, now + expiresIn * 1000L);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Vertex SA token 换取异常", e);
        }
    }

    private static byte[] rs256(byte[] data, String pkPem) {
        try {
            String clean = pkPem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(clean);
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(key);
            s.update(data);
            return s.sign();
        } catch (Exception e) {
            throw new IllegalStateException("SA JWT RS256 签名失败", e);
        }
    }

    private static String b64u(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
