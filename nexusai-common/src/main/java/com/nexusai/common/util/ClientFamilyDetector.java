package com.nexusai.common.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * User-Agent → 客户端家族推导：把上报 UA 归一为稳定的
 * 客户端家族标识（claude_code/codex/cursor/...），供请求日志筛选与「客户端」列展示。
 * <p>匹配按序短路（特异性高的在前），全部未中且 UA 含 SDK 痕迹归 {@code sdk}，否则返回 null（未知）。
 *
 * @author nexusai
 */
public final class ClientFamilyDetector {

    /** 有序匹配表：UA 小写包含关键串 → 家族标识（LinkedHashMap 保证特异性优先）。 */
    private static final Map<String, String> RULES = new LinkedHashMap<>();

    static {
        RULES.put("claude-cli", "claude_code");
        RULES.put("claude-code", "claude_code");
        RULES.put("codex_vscode", "codex_vscode");
        RULES.put("codex-vscode", "codex_vscode");
        RULES.put("codex", "codex");
        RULES.put("opencode", "opencode");
        RULES.put("gemini-cli", "gemini_cli");
        RULES.put("geminicli", "gemini_cli");
        RULES.put("qwen-code", "qwen_code");
        RULES.put("roo-code", "roo_code");
        RULES.put("roocode", "roo_code");
        RULES.put("kilocode", "kilocode");
        RULES.put("kilo-code", "kilocode");
        RULES.put("cherrystudio", "cherrystudio");
        RULES.put("cherry-studio", "cherrystudio");
        RULES.put("openui", "openui");
        RULES.put("cursor", "cursor");
        RULES.put("windsurf", "windsurf");
        RULES.put("continue", "continue");
        RULES.put("cline", "cline");
        RULES.put("aider", "aider");
        RULES.put("langchain", "langchain");
        RULES.put("llamaindex", "llamaindex");
        RULES.put("openai-node", "openai_js_sdk");
        RULES.put("openai/js", "openai_js_sdk");
        RULES.put("openai-python", "openai_python_sdk");
        RULES.put("openai/python", "openai_python_sdk");
        RULES.put("anthropic-sdk-typescript", "anthropic_js_sdk");
        RULES.put("anthropic/js", "anthropic_js_sdk");
        RULES.put("anthropic-sdk-python", "anthropic_python_sdk");
        RULES.put("anthropic-python", "anthropic_python_sdk");
        RULES.put("anthropic/python", "anthropic_python_sdk");
    }

    private ClientFamilyDetector() {
    }

    /** 推导客户端家族（null/空 UA 或未识别返回 null·上层落库为空即前端显示 unknown）。 */
    public static String detect(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        String ua = userAgent.toLowerCase();
        for (Map.Entry<String, String> e : RULES.entrySet()) {
            if (ua.contains(e.getKey())) {
                return e.getValue();
            }
        }
        // 通用 SDK 痕迹（如 "xxx/js 4.2.0"、"xxx/python 1.0"）归 sdk
        if (ua.contains("/js ") || ua.contains("/python ")) {
            return "sdk";
        }
        return null;
    }
}
