package com.nexusai.example;

/**
 * 冒烟示例占位。验证 /v1/chat/completions 闭环的 curl：
 * <pre>
 * curl http://localhost:8600/v1/chat/completions \
 *   -H "Authorization: Bearer sk-xxx" \
 *   -H "Content-Type: application/json" \
 *   -d '{"model":"gpt-4o","messages":[{"role":"user","content":"hi"}],"stream":true}'
 * </pre>
 *
 * @author nexusai
 */
public final class SmokeExample {

    private SmokeExample() {
    }
}
