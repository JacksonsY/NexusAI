package com.nexusai.relay.guard;

import com.nexusai.runtime.ModuleMetricsRegistry;
import com.nexusai.runtime.ModuleToggles;
import com.nexusai.runtime.RuntimeSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;

/**
 * 敏感信息保护：出站前对请求体做 PII 正则脱敏
 * （邮箱/手机号/身份证/银行卡 → 占位符），上游永远看不到原文；响应与日志不受影响。
 * 模块开关 {@code module.chat_pii_redaction.enabled}（默认关）+ 四类规则独立开关（均 5s 热生效）。
 * <p>替换值不含引号/反斜杠，对 JSON 字符串安全；失败放行原体（脱敏是尽力而为，不阻断主链）。
 * 指标经 {@link ModuleMetricsRegistry} 自注册（控制面模块管理页读，不直依赖本类）。
 *
 * @author nexusai
 */
@Slf4j
@Component
public class PiiRedactor {

    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    /** 中国大陆手机号（词边界防误伤长数字串） */
    private static final Pattern PHONE = Pattern.compile(
            "(?<![0-9])1[3-9]\\d{9}(?![0-9])");
    /** 18 位身份证（含末位 X） */
    private static final Pattern ID_CARD = Pattern.compile(
            "(?<![0-9])\\d{17}[0-9Xx](?![0-9])");
    /** 13-19 位银行卡/信用卡 */
    private static final Pattern BANK_CARD = Pattern.compile(
            "(?<![0-9])\\d{13,19}(?![0-9])");

    private final ModuleToggles moduleToggles;
    private final RuntimeSettings runtimeSettings;

    private final LongAdder redactedCount = new LongAdder();

    public PiiRedactor(ModuleToggles moduleToggles, RuntimeSettings runtimeSettings,
                       ModuleMetricsRegistry moduleMetrics) {
        this.moduleToggles = moduleToggles;
        this.runtimeSettings = runtimeSettings;
        moduleMetrics.register("chat_pii_redaction",
                () -> List.of(ModuleMetricsRegistry.metric("已脱敏请求", redactedCount.sum())));
    }

    /** 出站前脱敏；模块关闭或无命中返回原体。 */
    public byte[] redact(byte[] body) {
        if (body == null || body.length == 0 || !moduleToggles.enabled("chat_pii_redaction", false)) {
            return body;
        }
        try {
            String text = new String(body, StandardCharsets.UTF_8);
            String out = text;
            if (runtimeSettings.getBool("module.chat_pii_redaction.redact_email", true)) {
                out = EMAIL.matcher(out).replaceAll("[REDACTED_EMAIL]");
            }
            if (runtimeSettings.getBool("module.chat_pii_redaction.redact_phone", true)) {
                out = PHONE.matcher(out).replaceAll("[REDACTED_PHONE]");
            }
            if (runtimeSettings.getBool("module.chat_pii_redaction.redact_id_card", true)) {
                out = ID_CARD.matcher(out).replaceAll("[REDACTED_ID]");
            }
            if (runtimeSettings.getBool("module.chat_pii_redaction.redact_bank_card", false)) {
                // 默认关：13-19 位纯数字误伤面大（订单号/时间戳），按需开启
                out = BANK_CARD.matcher(out).replaceAll("[REDACTED_CARD]");
            }
            if (out.equals(text)) {
                return body;
            }
            redactedCount.increment();
            return out.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("PII 脱敏失败（放行原体）: {}", e.toString());
            return body;
        }
    }

}
