package com.nexusai.relay.guard;

/**
 * 护栏检查结果。{@code allowed=false} 时 {@code reason} 给拦截原因（进 OpenAI 错误体 + 审计）。
 *
 * @author nexusai
 */
public record GuardResult(boolean allowed, String reason) {

    public static final GuardResult PASS = new GuardResult(true, null);

    public static GuardResult block(String reason) {
        return new GuardResult(false, reason);
    }
}
