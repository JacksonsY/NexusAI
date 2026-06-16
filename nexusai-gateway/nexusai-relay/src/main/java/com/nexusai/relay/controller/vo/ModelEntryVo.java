package com.nexusai.relay.controller.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OpenAI 兼容 {@code /v1/models} 单模型条目 {@code {id,object,created,owned_by}}。
 * <p>组件名经 {@link JsonProperty} 固定为 snake_case，与历史 Map 键逐字一致（线序不可变）。
 *
 * @author nexusai
 */
public record ModelEntryVo(
        @JsonProperty("id") String id,
        @JsonProperty("object") String object,
        @JsonProperty("created") int created,
        @JsonProperty("owned_by") String ownedBy) {
}
