package com.nexusai.relay.guard;

import com.nexusai.relay.context.RelayContext;

/**
 * 内容安全护栏 SPI（缺口分析 C 类）：对入站 prompt 做安全检查（敏感词/PII/SSRF/越狱等）。
 * <p>多实现按 {@link #priority()} 顺序执行，任一 {@code block} 即拦截。<b>SPI 化</b>=新增检查只加实现类（厂商可插拔），
 * 不改 pipeline。默认关（{@code nexusai.guardrail.enabled}），开启时由 {@code GuardrailFilter} 在路由/预扣前调用。
 *
 * @author nexusai
 */
public interface Guardrail {

    /**
     * 检查 prompt 是否放行。
     *
     * @param prompt 入站请求正文（UTF-8 文本·可能含 messages JSON）
     * @param ctx    relay 上下文（token/model 等）
     * @return 放行或拦截（带原因）
     */
    GuardResult check(String prompt, RelayContext ctx);

    /** 执行优先级（小先执行）。 */
    default int priority() {
        return 100;
    }

    /** 本护栏名（日志/审计用）。 */
    default String name() {
        return getClass().getSimpleName();
    }
}
