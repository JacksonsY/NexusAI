package com.nexusai.relay.controller.vo;

import java.util.List;

/**
 * OpenAI 兼容 {@code /v1/models} 清单外壳 {@code {object:"list",data:[...]}}。
 *
 * @author nexusai
 */
public record ModelListVo(String object, List<ModelEntryVo> data) {
}
