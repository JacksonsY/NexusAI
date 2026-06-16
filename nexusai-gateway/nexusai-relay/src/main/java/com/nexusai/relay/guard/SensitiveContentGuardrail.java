package com.nexusai.relay.guard;

import com.nexusai.relay.context.RelayContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 默认护栏：敏感词拦截 + SSRF 启发式（缺口分析 C 类基础实现·可被厂商 SPI 扩展/替换）。
 * <ul>
 *   <li><b>敏感词</b>：{@code nexusai.guardrail.sensitive-words}（逗号分隔·大小写不敏感子串）命中即拦；</li>
 *   <li><b>SSRF 启发</b>：prompt 出现内网/元数据端点（127.0.0.1/localhost/169.254.169.254/10./192.168./172.16-31.）
 *       且 {@code nexusai.guardrail.ssrf-check=true} 时拦（防 prompt 诱导上游/工具访问内网）。</li>
 * </ul>
 * <b>始终装配</b>（无规则时 check 即 PASS）；是否执行由 {@code GuardrailFilter} 决定——全局开关
 * {@code nexusai.guardrail.enabled=true}（对所有 Key）或 Key 级 {@code feature_settings.content_guardrail} 开启（仅该 Key）。
 *
 * @author nexusai
 */
@Slf4j
@Component
public class SensitiveContentGuardrail implements Guardrail {

    private static final Pattern SSRF = Pattern.compile(
            "(127\\.0\\.0\\.1|localhost|169\\.254\\.169\\.254|metadata\\.google|10\\.\\d|192\\.168\\.|172\\.(1[6-9]|2\\d|3[01])\\.)",
            Pattern.CASE_INSENSITIVE);

    private final List<String> sensitiveWords = new ArrayList<>();
    private final boolean ssrfCheck;

    public SensitiveContentGuardrail(@Value("${nexusai.guardrail.sensitive-words:}") String words,
                                     @Value("${nexusai.guardrail.ssrf-check:false}") boolean ssrfCheck) {
        if (StringUtils.hasText(words)) {
            for (String w : words.split(",")) {
                if (StringUtils.hasText(w)) {
                    sensitiveWords.add(w.trim().toLowerCase());
                }
            }
        }
        this.ssrfCheck = ssrfCheck;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public GuardResult check(String prompt, RelayContext ctx) {
        if (!StringUtils.hasText(prompt)) {
            return GuardResult.PASS;
        }
        String lower = prompt.toLowerCase();
        for (String w : sensitiveWords) {
            if (lower.contains(w)) {
                return GuardResult.block("命中敏感词");
            }
        }
        if (ssrfCheck && SSRF.matcher(prompt).find()) {
            return GuardResult.block("疑似 SSRF（内网/元数据端点）");
        }
        return GuardResult.PASS;
    }
}
