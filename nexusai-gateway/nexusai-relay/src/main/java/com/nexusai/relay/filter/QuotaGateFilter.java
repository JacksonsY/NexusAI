package com.nexusai.relay.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.billing.config.ModelPriceService;
import com.nexusai.billing.config.UsdRate;
import com.nexusai.billing.meter.UsageMeter;
import com.nexusai.billing.quota.FreezeReconciler;
import com.nexusai.billing.quota.QuotaLedger;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.relay.RelayFormats;
import com.nexusai.relay.context.RelayContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/**
 * ⑦ USD 强闸门（最后一步）：按模型结构化 USD 价格精确估算 → Redis 原子 freeze 预扣（micro-USD），
 * 不足即拒。<b>必须排最后</b>——前置任何失败都在预扣之前，无需回退；流末由 orchestrator 按真实 usage 多退少补。
 *
 * @author nexusai
 */
@Component
@RequiredArgsConstructor
public class QuotaGateFilter implements RelayFilter {

    /**
     * 无 max_tokens 声明时的保守补全上界（评审 H1：固定 512 系统性低估·长流 settle 补扣击穿负余额）。
     * 4096 覆盖绝大多数对话补全；客户端声明 max_tokens 时一律以其为准，保证 freeze ≥ settle。
     */
    private static final int DEFAULT_EST_COMPLETION = 4096;
    /** 单请求图片张数估算上限（防 n 异常巨值冻结溢出·OpenAI images n 上限为 10）。 */
    private static final int MAX_IMAGE_COUNT = 10;

    private final ModelPriceService modelPriceService;
    private final UsageMeter usageMeter;
    private final QuotaLedger quotaLedger;
    private final FreezeReconciler freezeReconciler;

    /** 拒绝未建价模型（默认开·防「可路由但 nai_model 无价格行」的模型 freeze≈0 白嫖；置 false 退回旧的按 0 放行行为）。 */
    @Value("${nexusai.billing.deny-unpriced-model:true}")
    private boolean denyUnpricedModel;

    @Override
    public void filter(RelayContext ctx, ServerWebExchange exchange) {
        UsdRate price = modelPriceService.priceFor(ctx.getPeek().model());
        // 🔴 未建价模型（UNKNOWN 哨兵·非显式零价行）：freeze≈0 + settle 按 0 = 白嫖且无闸门，拒绝计费
        if (denyUnpricedModel && price.isUnknown()) {
            throw GatewayException.of(GatewayErrorCode.MODEL_PRICE_UNCONFIGURED,
                    "模型未在 nai_model 配置价格，拒绝计费: " + ctx.getPeek().model());
        }
        ctx.setUsdRate(price);
        // 🔴 freeze 必须 >= 任何可能的 settle（含 perImageFee）——否则 settle 补扣可击穿强闸门到负余额（评审 H1）。
        // 模态判定必须与 SettlementService 同源（RelayFormats.modalityOf）：否则如 Gemini :generateContent
        // 在此被判无补全而 settle 按 chat 计补全 → settle>freeze 击穿。image 预留 1 张图费；
        // chat 的补全上界取请求声明的 max_tokens（真实上界），未声明才退到保守默认；其余模态不估补全。
        String modality = RelayFormats.modalityOf(ctx.getPath());
        boolean image = "image".equals(modality);
        boolean hasCompletion = "chat".equals(modality);
        // 🔴 multipart（音频转写/图编）body 含二进制 payload，字节数不是 token 代理：bytes/4 会把 25MB 音频估成
        // 数百万 token → 冻结数十美元，且转写响应常无 usage → settle 按 estimated=frozen 实扣（巨额超收·随文件线性放大）。
        // 故 multipart 不按字节估 prompt（按 perRequest/perImage 计价）；settle 侧同源判定（见 SettlementService）。
        boolean multipart = isMultipart(ctx.getContentType());
        int estPrompt = multipart ? 0 : ctx.getBody().length / 4;    // 粗估输入 token（multipart 除外）
        int declaredMax = ctx.getPeek() == null ? 0 : ctx.getPeek().maxTokens();
        int estCompletion = hasCompletion ? (declaredMax > 0 ? declaredMax : DEFAULT_EST_COMPLETION) : 0;
        // 🔴 image 张数取请求 n（默认 1·封顶 MAX_IMAGE_COUNT）一次解析存 ctx·settle 同源复用：n>1 时 freeze/settle 均按张计，杜绝少计费
        int images = image ? parseImageCount(ctx.getBody()) : 0;
        if (image) {
            ctx.setImageCount(images);
        }
        // 🔴 freeze≥settle：prompt 计价须取 input/writeCached 上界——cache_creation token 按 writeCachedRate(可达 input×1.25)
        // 结算，若 freeze 只按 inputRate 估，缓存写多的请求 settle>freeze 会击穿强闸门为负。两极端取 max 作上界。
        long estAllInput = usageMeter.microUsdFor(price, estPrompt, estCompletion, 0, 0, images);
        long estAllCreation = usageMeter.microUsdFor(price, estPrompt, estCompletion, 0, estPrompt, images);
        long est = Math.max(estAllInput, estAllCreation);
        quotaLedger.freeze(ctx.getToken(), est);
        ctx.setFrozenUsdAmount(est);
        // 🔴 崩溃对账登记：进程若在此后、settle 之前崩溃（长流式窗口），冻结既无内联 settle 也无 outbox 行 →
        // 由 FreezeReconciler 扫超阈值孤儿冻结全额退（dedup 与正常 settle 互斥·不双退）。est≤0 内部不登记。
        freezeReconciler.record(ctx.getRequestId(), ctx.getToken().getId(), est, System.currentTimeMillis());
    }

    /** 是否 multipart/form-data（音频/文件上传·二进制 body·不按字节估 token）。 */
    private static boolean isMultipart(String contentType) {
        return contentType != null && contentType.toLowerCase().contains("multipart/form-data");
    }

    /** 解析图片生成请求体的 {@code n}（默认 1·夹在 [1, MAX_IMAGE_COUNT]）；体非法/缺失退 1。 */
    private static int parseImageCount(byte[] body) {
        if (body == null || body.length == 0) {
            return 1;
        }
        try {
            JsonNode n = JsonUtils.readTree(body).path("n");
            int count = n.isNumber() ? n.asInt(1) : 1;
            return Math.max(1, Math.min(count, MAX_IMAGE_COUNT));
        } catch (Exception e) {
            return 1;
        }
    }

    @Override
    public int getOrder() {
        return ORDER_QUOTA_GATE;
    }
}
