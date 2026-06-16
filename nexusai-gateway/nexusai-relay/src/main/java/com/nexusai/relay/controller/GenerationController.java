package com.nexusai.relay.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.persistence.mapper.NaiUsageLogMapper;
import com.nexusai.persistence.po.NaiToken;
import com.nexusai.persistence.po.NaiUsageLog;
import com.nexusai.auth.ApiKeyAuthService;
import com.nexusai.relay.controller.vo.GenerationDataVo;
import com.nexusai.relay.controller.vo.GenerationResponseVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 单请求计量明细查询（R2·{@code GET /api/v1/generation}）：接入应用按响应头
 * {@code X-Nexusai-Request-Id} 回查本次请求的 usage 四元组 + 扣减 cost（USD），作为其自身商业计费
 * （积分折算/对账）的原料——流式请求响应头无法承载结算终值，一律以本端点为准。
 * <p>sk- 鉴权·仅可查<b>本项目</b>记录（跨项目/不存在一律 404，不泄露他项目请求存在性）。
 * 数据源 {@code nai_usage_log}（settle 时同步落库·与 UsageEvent 同源），流末毫秒级可见。
 *
 * @author nexusai
 */
@RestController
@RequiredArgsConstructor
public class GenerationController {

    private final ApiKeyAuthService authService;
    private final NaiUsageLogMapper usageLogMapper;

    @GetMapping("/v1/generation/{requestId}")
    public Mono<GenerationResponseVo> generation(@PathVariable String requestId, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            NaiToken token = authService.authenticate(
                    exchange.getRequest().getHeaders().getFirst(GatewayConstants.AUTH_HEADER));
            NaiUsageLog usageLog = usageLogMapper.selectOne(new LambdaQueryWrapper<NaiUsageLog>()
                    .eq(NaiUsageLog::getRequestId, requestId).last("LIMIT 1"));
            if (usageLog == null || token.getProjectCode() == null
                    || !token.getProjectCode().equals(usageLog.getProjectCode())) {
                throw GatewayException.of(GatewayErrorCode.GENERATION_NOT_FOUND);
            }
            // 仅外吐应用可见口径（channelId/keyId/costBasis 为网关内部信息·不外泄）
            GenerationDataVo data = new GenerationDataVo(
                    usageLog.getRequestId(),
                    usageLog.getModel(),
                    usageLog.getPromptTokens(),
                    usageLog.getCompletionTokens(),
                    usageLog.getCacheTokens(),
                    usageLog.getCacheCreationTokens(),
                    usageLog.getTotalTokens(),
                    usageLog.getCostUsd(),
                    usageLog.getStatus(),
                    usageLog.getCreateDt());
            return new GenerationResponseVo(data);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
