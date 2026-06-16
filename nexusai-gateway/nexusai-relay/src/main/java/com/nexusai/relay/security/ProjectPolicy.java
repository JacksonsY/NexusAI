package com.nexusai.relay.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.persistence.po.NaiChannel;
import com.nexusai.persistence.po.NaiProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * 项目访问策略（解析态）：约束项目下全部 sk- Key 的
 * 可用模型 / 可用能力 / 可走渠道（id 白名单 + 标签匹配）。各集合空 = 该维度不限制。
 * <p>JSON 字段解析失败按操作侧配置 bug 处理 <b>fail-open</b>（计空集=不限），与 model_limits 口径一致。
 *
 * @author nexusai
 */
public record ProjectPolicy(Set<String> models, Set<String> modalities,
                            Set<Long> channelIds, Set<String> channelTags, String tagsMatchMode,
                            int rpmLimit, int tpmLimit) {

    private static final Logger log = LoggerFactory.getLogger(ProjectPolicy.class);

    /** 全不限策略（项目未配置任何限制/无归属项目时复用）。 */
    public static final ProjectPolicy UNRESTRICTED = new ProjectPolicy(Set.of(), Set.of(), Set.of(), Set.of(), "any", 0, 0);

    public static ProjectPolicy of(NaiProject p) {
        if (p == null) {
            return UNRESTRICTED;
        }
        return new ProjectPolicy(
                strSet(p.getAllowedModels()),
                strSet(p.getAllowedModalities()),
                longSet(p.getAllowedChannelIds()),
                strSet(p.getAllowedChannelTags()),
                p.getChannelTagsMatchMode() == null || p.getChannelTagsMatchMode().isBlank()
                        ? "any" : p.getChannelTagsMatchMode(),
                p.getRpmLimit() == null ? 0 : p.getRpmLimit(),
                p.getTpmLimit() == null ? 0 : p.getTpmLimit());
    }

    /** 本策略是否完全不限制（快速短路）。 */
    public boolean unrestricted() {
        return models.isEmpty() && modalities.isEmpty() && channelIds.isEmpty() && channelTags.isEmpty();
    }

    public boolean modelAllowed(String model) {
        return models.isEmpty() || models.contains(model);
    }

    public boolean modalityAllowed(String modality) {
        return modalities.isEmpty() || modalities.contains(modality);
    }

    /** 渠道是否可用：id 白名单 ∧ 标签匹配（两维度独立·都为空=不限）。 */
    public boolean channelAllowed(NaiChannel channel) {
        if (!channelIds.isEmpty() && !channelIds.contains(channel.getId())) {
            return false;
        }
        if (channelTags.isEmpty()) {
            return true;
        }
        Set<String> tags = strSet(channel.getTags());
        return switch (tagsMatchMode) {
            case "all" -> tags.containsAll(channelTags);
            case "none" -> channelTags.stream().noneMatch(tags::contains);
            default -> channelTags.stream().anyMatch(tags::contains);   // any
        };
    }

    private static Set<String> strSet(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            List<String> list = JsonUtils.parse(json, new TypeReference<List<String>>() {
            });
            return list == null ? Set.of() : Set.copyOf(list);
        } catch (Exception e) {
            // fail-open（计空集=不限）：记日志使操作侧配置 bug 可见，而非静默吞掉
            log.warn("项目策略字符串集解析失败（按不限处理）: {}", e.toString());
            return Set.of();
        }
    }

    private static Set<Long> longSet(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            List<Long> list = JsonUtils.parse(json, new TypeReference<List<Long>>() {
            });
            return list == null ? Set.of() : Set.copyOf(list);
        } catch (Exception e) {
            // fail-open（计空集=不限）：记日志使操作侧配置 bug 可见，而非静默吞掉
            log.warn("项目策略渠道 id 集解析失败（按不限处理）: {}", e.toString());
            return Set.of();
        }
    }
}
