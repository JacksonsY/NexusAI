package com.nexusai.relay.affinity;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.relay.context.RelayContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 会话亲和键三级提取（多源标识 + 摘要链 + tokenId 兜底）：
 * <ol>
 *   <li><b>客户端稳定标识</b>（天然跨轮）：{@code metadata.user_id}（Claude Code 等）→
 *       {@code prompt_cache_key}（OpenAI responses）→ {@code user}（OpenAI chat）</li>
 *   <li><b>摘要链最长前缀匹配</b>（DigestSessionStore）：{@code s:<h8>-u:<h8>-a:<h8>-…} 逐消息短 hash，
 *       追加消息后逐段截断仍命中旧会话——修复旧「前缀整体 hash」每轮都变、粘性跨轮永不命中的缺陷</li>
 *   <li><b>tokenId 兜底</b>（粗粒度但安全）</li>
 * </ol>
 * 结果缓存进 {@link RelayContext}（route 与 settle 各用一次，只算一遍）。
 *
 * @author nexusai
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AffinityKeyResolver {

    /** 提取结果：亲和键 + 来源（metadata/digest/token·监控列表展示）。 */
    public record AffinityKey(String key, String source) {
    }

    private final DigestSessionStore digestStore;

    public AffinityKey resolve(RelayContext ctx) {
        if (ctx.getAffinityKey() != null) {
            return new AffinityKey(ctx.getAffinityKey(), ctx.getAffinitySource());
        }
        AffinityKey result = doResolve(ctx);
        ctx.setAffinityKey(result.key());
        ctx.setAffinitySource(result.source());
        return result;
    }

    private AffinityKey doResolve(RelayContext ctx) {
        long tokenId = ctx.getToken().getId();
        try {
            JsonNode root = JsonUtils.readTree(ctx.getBody());
            if (root != null) {
                // ① 客户端稳定标识
                String stable = firstNonBlank(
                        root.path("metadata").path("user_id").asText(null),
                        root.path("prompt_cache_key").asText(null),
                        root.path("user").asText(null));
                if (stable != null) {
                    return new AffinityKey(tokenId + ":uid:" + shortHash(stable), "metadata");
                }
                // ② 摘要链最长前缀匹配
                String chain = digestChain(root);
                if (chain != null) {
                    String session = digestStore.resolveSession(tokenId, chain);
                    if (session != null) {
                        return new AffinityKey(tokenId + ":dg:" + session, "digest");
                    }
                }
            }
        } catch (Exception e) {
            log.debug("亲和键解析失败回退 tokenId requestId={}: {}", ctx.getRequestId(), e.toString());
        }
        // ③ tokenId 兜底
        return new AffinityKey(String.valueOf(tokenId), "token");
    }

    /**
     * 逐消息摘要链：{@code s:<h8>} + 每条消息 {@code u|a:<h8>}，
     * '-' 连接。content 取原始 JSON 串 hash（对协议无感·OpenAI/Anthropic 通吃）。
     */
    private static String digestChain(JsonNode root) {
        JsonNode messages = root.get("messages");
        if (messages == null || !messages.isArray() || messages.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(messages.size() * 11 + 12);
        JsonNode system = root.get("system");
        if (system != null && !system.isNull()) {
            sb.append("s:").append(shortHash(system.toString()));
        }
        for (JsonNode m : messages) {
            if (!sb.isEmpty()) {
                sb.append('-');
            }
            String role = "assistant".equals(m.path("role").asText("")) ? "a" : "u";
            sb.append(role).append(':').append(shortHash(m.path("content").toString()));
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** SHA-256 前 8 hex（链段短 hash·摘要键素材）。 */
    private static String shortHash(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 4);
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
