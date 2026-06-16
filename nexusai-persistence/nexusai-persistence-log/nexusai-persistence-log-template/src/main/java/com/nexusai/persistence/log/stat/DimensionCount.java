package com.nexusai.persistence.log.stat;

/**
 * 按维度（渠道/模型）分组的请求总量 + 成功数（Dashboard 成功率排行）。
 * <p>{@code key} 为维度键（如渠道 channelId）；维度名称（渠道名）由上层 join 补全，本层只产计数。
 *
 * @param key     维度键（渠道 channelId 等·可为 null）
 * @param total   该维度总请求数（含失败）
 * @param success 该维度成功数（http_status &lt; 400）
 * @author nexusai
 */
public record DimensionCount(Long key, long total, long success) {
}
