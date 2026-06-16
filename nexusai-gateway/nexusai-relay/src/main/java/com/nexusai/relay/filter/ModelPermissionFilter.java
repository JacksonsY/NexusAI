package com.nexusai.relay.filter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.relay.context.RelayContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;

/**
 * ③ Model Permission：校验本 Key 的可用模型白名单（nai_token.model_limits），
 * 越权即拒（403 MODEL_NOT_PERMITTED）。白名单为空/未配 = 不限制（放行全部）。
 * <p>model_limits 约定为 JSON 字符串数组（如 {@code ["gpt-4o","gpt-4o-mini"]}）；解析失败按操作侧配置 bug
 * 处理，<b>fail-open</b>（warn 后放行），与既有 JSON 字段（model_mapping/header_override）失败容忍口径一致。
 *
 * @author nexusai
 */
@Slf4j
@Component
public class ModelPermissionFilter implements RelayFilter {

    @Override
    public void filter(RelayContext ctx, ServerWebExchange exchange) {
        String limitsJson = ctx.getToken().getModelLimits();
        if (limitsJson == null || limitsJson.isBlank()) {
            return;
        }
        List<String> allowed;
        try {
            allowed = JsonUtils.parse(limitsJson, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("model_limits 解析失败 tokenId={}，放行（fail-open）: {}", ctx.getToken().getId(), limitsJson);
            return;
        }
        if (allowed == null || allowed.isEmpty()) {
            return;
        }
        String model = ctx.getPeek().model();
        if (!allowed.contains(model)) {
            throw GatewayException.of(GatewayErrorCode.MODEL_NOT_PERMITTED,
                    "该 Key 无权访问模型: " + model);
        }
    }

    @Override
    public int getOrder() {
        return ORDER_MODEL_PERMISSION;
    }
}
