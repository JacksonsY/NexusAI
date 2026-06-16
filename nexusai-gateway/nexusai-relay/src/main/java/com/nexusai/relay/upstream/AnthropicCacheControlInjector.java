package com.nexusai.relay.upstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.common.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import com.nexusai.runtime.RuntimeSettings;

/**
 * Anthropic cache_control 自动注入（strict mode）：
 * 对发往 Anthropic 协议渠道的请求体自动规划缓存断点，让没显式写 cache_control 的客户端也吃到
 * prompt caching 折扣。规则（Anthropic 上限 4 断点）：
 * <ol>
 *   <li>归一化：消息 content 字符串 → 块数组；system 字符串 → 块数组</li>
 *   <li>先清空全部既有断点再重建（防历史断点漂移抖动）</li>
 *   <li>结构锚点：tools 最后一项 + system 最后一块（内容稳定·官方推荐）</li>
 *   <li>消息锚点：最后一个可缓存块必打；可缓存块 ≥ {@value #BLOCK_WINDOW} 时再在
 *       「距末尾约 {@value #BLOCK_WINDOW} 块」的窗口边界补第二个（优先左侧·更靠近稳定前缀）</li>
 *   <li>thinking/redacted_thinking 与空 text 块绝不打点</li>
 * </ol>
 * 开关 {@code nexusai.cache.anthropic-auto-cache.enabled}（默认开）。注入失败原体放行（绝不破坏主链）。
 *
 * @author nexusai
 */
@Slf4j
@Component
public class AnthropicCacheControlInjector {

    private static final int MAX_BREAKPOINTS = 4;
    /** 默认窗口块数；模块设置抽屉 module.anthropic_auto_cache.block_window 热读覆盖 */
    private static final int BLOCK_WINDOW = 20;

    private final boolean enabled;
    private final RuntimeSettings runtimeSettings;

    public AnthropicCacheControlInjector(
            RuntimeSettings runtimeSettings,
            @Value("${nexusai.cache.anthropic-auto-cache.enabled:true}") boolean enabled) {
        this.runtimeSettings = runtimeSettings;
        this.enabled = enabled;
    }

    private int blockWindow() {
        return Math.max(4, runtimeSettings.getInt("module.anthropic_auto_cache.block_window", BLOCK_WINDOW));
    }

    /** 注入断点（body 为 Anthropic messages 体）。失败/关闭返回原体。 */
    public byte[] inject(byte[] body) {
        if (!enabled || body == null || body.length == 0) {
            return body;
        }
        try {
            JsonNode root = JsonUtils.readTree(body);
            if (!(root instanceof ObjectNode obj) || !obj.has("messages")) {
                return body;
            }
            normalize(obj);
            clearBreakpoints(obj);
            int structural = ensureStructuralBreakpoints(obj);
            int remaining = MAX_BREAKPOINTS - structural;
            if (remaining > 0) {
                List<ObjectNode> refs = cacheableBlocks(obj);
                int anchors = Math.min(desiredMessageAnchors(refs.size()), remaining);
                injectMessageAnchors(refs, anchors);
            }
            return JsonUtils.toJson(obj).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("cache_control 注入失败原体放行: {}", e.toString());
            return body;
        }
    }

    /** 消息 content 字符串 → 单 text 块数组；system 字符串 → 单 text 块数组。 */
    private static void normalize(ObjectNode root) {
        JsonNode messages = root.get("messages");
        if (messages instanceof ArrayNode arr) {
            for (JsonNode m : arr) {
                if (m instanceof ObjectNode mo && mo.path("content").isTextual()) {
                    String text = mo.get("content").asText();
                    if (!text.isEmpty()) {
                        ArrayNode blocks = mo.arrayNode();
                        blocks.addObject().put("type", "text").put("text", text);
                        mo.set("content", blocks);
                    }
                }
            }
        }
        JsonNode system = root.get("system");
        if (system != null && system.isTextual() && !system.asText().isEmpty()) {
            ArrayNode blocks = root.arrayNode();
            blocks.addObject().put("type", "text").put("text", system.asText());
            root.set("system", blocks);
        }
    }

    private static void clearBreakpoints(ObjectNode root) {
        for (String field : new String[]{"tools", "system"}) {
            JsonNode node = root.get(field);
            if (node instanceof ArrayNode arr) {
                arr.forEach(n -> {
                    if (n instanceof ObjectNode o) {
                        o.remove("cache_control");
                    }
                });
            }
        }
        JsonNode messages = root.get("messages");
        if (messages instanceof ArrayNode arr) {
            for (JsonNode m : arr) {
                JsonNode content = m.path("content");
                if (content instanceof ArrayNode blocks) {
                    blocks.forEach(b -> {
                        if (b instanceof ObjectNode o) {
                            o.remove("cache_control");
                        }
                    });
                }
            }
        }
    }

    /** tools 尾 + system 尾打结构锚点，返回实际打点数。 */
    private static int ensureStructuralBreakpoints(ObjectNode root) {
        int count = 0;
        JsonNode tools = root.get("tools");
        if (tools instanceof ArrayNode arr && !arr.isEmpty() && arr.get(arr.size() - 1) instanceof ObjectNode last) {
            last.putObject("cache_control").put("type", "ephemeral");
            count++;
        }
        JsonNode system = root.get("system");
        if (system instanceof ArrayNode arr && !arr.isEmpty() && arr.get(arr.size() - 1) instanceof ObjectNode last) {
            last.putObject("cache_control").put("type", "ephemeral");
            count++;
        }
        return count;
    }

    /** 收集消息内全部可缓存块（thinking/空 text 排除）。 */
    private static List<ObjectNode> cacheableBlocks(ObjectNode root) {
        List<ObjectNode> refs = new ArrayList<>();
        JsonNode messages = root.get("messages");
        if (messages instanceof ArrayNode arr) {
            for (JsonNode m : arr) {
                JsonNode content = m.path("content");
                if (content instanceof ArrayNode blocks) {
                    for (JsonNode b : blocks) {
                        if (b instanceof ObjectNode o && cacheable(o)) {
                            refs.add(o);
                        }
                    }
                }
            }
        }
        return refs;
    }

    private static boolean cacheable(ObjectNode block) {
        String type = block.path("type").asText("");
        return switch (type) {
            case "thinking", "redacted_thinking" -> false;
            case "text" -> !block.path("text").asText("").isEmpty();
            default -> true;
        };
    }

    private int desiredMessageAnchors(int cacheableBlocks) {
        if (cacheableBlocks == 0) {
            return 0;
        }
        return cacheableBlocks >= blockWindow() ? 2 : 1;
    }

    private void injectMessageAnchors(List<ObjectNode> refs, int target) {
        if (target <= 0 || refs.isEmpty()) {
            return;
        }
        // 第一优先级：最后一个可缓存块（官方推荐会话末尾断点）
        refs.get(refs.size() - 1).putObject("cache_control").put("type", "ephemeral");
        if (target > 1) {
            // 第二优先级：距末尾约 BLOCK_WINDOW 块的窗口边界（优先左侧·靠近稳定前缀）
            int idx = Math.max(refs.size() - 1 - blockWindow(), 0);
            for (int i = idx; i >= 0; i--) {
                if (!refs.get(i).has("cache_control")) {
                    refs.get(i).putObject("cache_control").put("type", "ephemeral");
                    return;
                }
            }
            for (int i = idx + 1; i < refs.size(); i++) {
                if (!refs.get(i).has("cache_control")) {
                    refs.get(i).putObject("cache_control").put("type", "ephemeral");
                    return;
                }
            }
        }
    }
}
