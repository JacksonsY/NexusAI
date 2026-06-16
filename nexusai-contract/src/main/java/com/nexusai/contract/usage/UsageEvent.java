package com.nexusai.contract.usage;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * UsageEvent 用量回传（网关→业务面·Redis Stream 回传·2026-06-14 由无类型 Map 提升为强类型 wire 记录）。
 * <p>网关与业务面共享的单一真理源：网关侧（relay/billing）结算后发布、业务面消费计价，二者共用本记录禁止再各自镜像字段。
 * 与 {@code nai_usage_log}（同步落库·权威账本）同源——Stream 仅作加速通道，<b>Stream 挂了/脏消息丢字段仍可由 outbox 重建计费</b>。
 * <p><b>Redis Stream 物理形态是 {@code Map<String,String>} field-value</b>，故本记录在 Stream 边界经 {@link #toMap()}/{@link #fromMap(Map)}
 * 双向转换：发布端 {@code toMap()} 拿到编译期安全的字段；消费端 {@code fromMap()} <b>逐字段容错降级（解析失败→null·绝不抛异常阻断消费或 outbox 重投）</b>。
 * 字段名常量（{@code F_*}）是 wire key 的单一真理源，两端严禁各写裸字符串。
 *
 * @param requestId           请求唯一标识
 * @param projectCode         项目维度归集（nai_project.project_code）
 * @param workspaceId         项目内工作区（nai_workspace.id·B2C 为填·B2B 为空）
 * @param model               请求模型
 * @param channelId           渠道 ID
 * @param keyId               渠道密钥 ID
 * @param promptTokens        prompt token 数
 * @param completionTokens    completion token 数
 * @param cachedTokens        缓存命中 token 数
 * @param cacheCreationTokens 写缓存 token 数
 * @param totalTokens         总 token 数
 * @param costUsd             扣减 USD（BigDecimal 精确十进制·禁浮点近似）
 * @param usageSource         用量来源：parsed|estimated|image-fallback|client-abort|failed|settle-error（审计 usage 丢失率）
 * @author nexusai
 */
public record UsageEvent(
        String requestId,
        String projectCode,
        Long workspaceId,
        String model,
        Long channelId,
        Long keyId,
        Integer promptTokens,
        Integer completionTokens,
        Integer cachedTokens,
        Integer cacheCreationTokens,
        Integer totalTokens,
        BigDecimal costUsd,
        String usageSource) {

    // ===== wire field key 单一真理源（两端共用·禁止裸字符串） =====
    public static final String F_REQUEST_ID = "requestId";
    public static final String F_PROJECT_CODE = "projectCode";
    public static final String F_WORKSPACE_ID = "workspaceId";
    public static final String F_MODEL = "model";
    public static final String F_CHANNEL_ID = "channelId";
    public static final String F_KEY_ID = "keyId";
    public static final String F_PROMPT_TOKENS = "promptTokens";
    public static final String F_COMPLETION_TOKENS = "completionTokens";
    public static final String F_CACHED_TOKENS = "cachedTokens";
    public static final String F_CACHE_CREATION_TOKENS = "cacheCreationTokens";
    public static final String F_TOTAL_TOKENS = "totalTokens";
    public static final String F_COST_USD = "costUsd";
    public static final String F_USAGE_SOURCE = "usageSource";

    /**
     * 记录 → Redis Stream entries（String KV）。
     * <p>null 编码为空串（workspaceId/null 与历史 wire 兼容）；数值 token 缺省 0；costUsd 用 {@code toPlainString} 保 6 位精度。
     */
    public Map<String, String> toMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(F_REQUEST_ID, str(requestId));
        m.put(F_PROJECT_CODE, str(projectCode));
        m.put(F_WORKSPACE_ID, workspaceId == null ? "" : workspaceId.toString());
        m.put(F_MODEL, str(model));
        m.put(F_CHANNEL_ID, channelId == null ? "" : channelId.toString());
        m.put(F_KEY_ID, keyId == null ? "" : keyId.toString());
        m.put(F_PROMPT_TOKENS, num(promptTokens));
        m.put(F_COMPLETION_TOKENS, num(completionTokens));
        m.put(F_CACHED_TOKENS, num(cachedTokens));
        m.put(F_CACHE_CREATION_TOKENS, num(cacheCreationTokens));
        m.put(F_TOTAL_TOKENS, num(totalTokens));
        m.put(F_COST_USD, costUsd == null ? "0" : costUsd.toPlainString());
        m.put(F_USAGE_SOURCE, str(usageSource));
        return m;
    }

    /**
     * Redis Stream entries → 记录（<b>逐字段容错降级</b>）。
     * <p>缺字段/脏数据：String 字段空串→null；数值/金额解析失败→null。<b>绝不抛异常</b>——消费端不因单条脏消息阻断，
     * 计费正确性由 {@code nai_usage_log} 权威账本兜底。
     */
    public static UsageEvent fromMap(Map<String, String> m) {
        if (m == null) {
            return null;
        }
        return new UsageEvent(
                emptyToNull(m.get(F_REQUEST_ID)),
                emptyToNull(m.get(F_PROJECT_CODE)),
                parseLong(m.get(F_WORKSPACE_ID)),
                emptyToNull(m.get(F_MODEL)),
                parseLong(m.get(F_CHANNEL_ID)),
                parseLong(m.get(F_KEY_ID)),
                parseInt(m.get(F_PROMPT_TOKENS)),
                parseInt(m.get(F_COMPLETION_TOKENS)),
                parseInt(m.get(F_CACHED_TOKENS)),
                parseInt(m.get(F_CACHE_CREATION_TOKENS)),
                parseInt(m.get(F_TOTAL_TOKENS)),
                parseBig(m.get(F_COST_USD)),
                emptyToNull(m.get(F_USAGE_SOURCE)));
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    private static String num(Integer i) {
        return i == null ? "0" : i.toString();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static Long parseLong(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseInt(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal parseBig(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
