package com.nexusai.relay.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.persistence.mapper.NaiModelMapper;
import com.nexusai.persistence.po.NaiModel;
import com.nexusai.persistence.po.NaiToken;
import com.nexusai.auth.ApiKeyAuthService;
import com.nexusai.relay.controller.vo.ModelEntryVo;
import com.nexusai.relay.controller.vo.ModelListVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * OpenAI 兼容 /v1/models（客户查询可用模型真理源）。sk- 鉴权（不走 RelayOrchestrator 过滤链·自行鉴权），
 * 返回 nai_model 的 OpenAI 格式清单 {object:list,data:[{id,object:model,...}]}（/v1/models 真理源）。
 *
 * @author nexusai
 */
@RestController
@RequiredArgsConstructor
public class ModelsController {

    private final ApiKeyAuthService authService;
    private final NaiModelMapper modelMapper;

    /** OpenAI 模型条目固定字段：object=model、created 占位 0、owned_by=平台标识。 */
    private static final String MODEL_OBJECT = "model";
    private static final int MODEL_CREATED_PLACEHOLDER = 0;
    private static final String MODEL_OWNER = "nexusai";
    /** {@code /v1/models} 清单外壳 object 值。 */
    private static final String LIST_OBJECT = "list";

    @GetMapping("/v1/models")
    public Mono<ModelListVo> models(ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            // sk- 鉴权（无效抛 GatewayException → GlobalExceptionHandler 转 OpenAI 错误体）
            NaiToken token = authService.authenticate(
                    exchange.getRequest().getHeaders().getFirst(GatewayConstants.AUTH_HEADER));
            // 评审 M7/H5：按本 key 的 modelLimits 在【库级】过滤——OpenAI 语义应返回"此 key 可访问的模型"而非系统全表，
            // 且避免把全表载入内存（H5：/v1/models 不再全表查库）。allowed=null（不限）时才全表。
            Set<String> allowed = parseModelLimits(token.getModelLimits());
            List<NaiModel> models = (allowed == null)
                    ? modelMapper.selectList(null)
                    : modelMapper.selectList(new LambdaQueryWrapper<NaiModel>().in(NaiModel::getModelKey, allowed));
            List<ModelEntryVo> data = new ArrayList<>();
            for (NaiModel m : models) {
                data.add(new ModelEntryVo(m.getModelKey(), MODEL_OBJECT, MODEL_CREATED_PLACEHOLDER, MODEL_OWNER));
            }
            return new ModelListVo(LIST_OBJECT, data);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 解析 token.modelLimits（JSON 数组或逗号/空白分隔）→ 允许的模型键集；null/空=不限（unlimited·返回全部）。 */
    private static Set<String> parseModelLimits(String limits) {
        if (limits == null || limits.isBlank()) {
            return null;
        }
        String s = limits.trim();
        Set<String> set = new HashSet<>();
        if (s.startsWith("[")) {
            try {
                for (JsonNode n : JsonUtils.readTree(s)) {
                    if (!n.asText().isBlank()) {
                        set.add(n.asText().trim());
                    }
                }
                return set.isEmpty() ? null : set;
            } catch (Exception ignore) {
                // 非 JSON 数组 → 回退分隔符解析
            }
        }
        for (String part : s.split("[,\\s]+")) {
            if (!part.isBlank()) {
                set.add(part.trim());
            }
        }
        return set.isEmpty() ? null : set;
    }
}
