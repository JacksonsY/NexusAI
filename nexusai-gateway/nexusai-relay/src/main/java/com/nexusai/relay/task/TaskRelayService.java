package com.nexusai.relay.task;

import com.nexusai.billing.config.ModelPriceService;
import com.nexusai.billing.config.UsdRate;
import com.nexusai.billing.meter.UsageMeter;
import com.nexusai.billing.quota.QuotaLedger;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.persistence.po.NaiTask;
import com.nexusai.persistence.po.NaiToken;
import com.nexusai.auth.ApiKeyAuthService;
import com.nexusai.task.TaskService;
import com.nexusai.task.spi.TaskAdapterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 异步任务网关服务（OpenAI /v1/tasks·Midjourney /mj·Suno /suno 三入站共用）：鉴权 → pre-charge 冻结 → 持久化任务 → 查询。
 * <p>上游 submit/fetch 协议适配 + 轮询驱动是 {@code TaskAdapter} SPI 的 per-provider 集成点（{@code TaskPoller} 驱动）；
 * 本服务只管<b>入站侧</b>的鉴权/冻结/持久化/项目作用域校验，遵循「提交→DB→查询」模型。
 *
 * @author nexusai
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TaskRelayService {

    /** 异步任务 pre-charge 估算当量（任务计费在 业务面 按结果精算；此处仅冻结兜底·与 AsyncTaskController 同口径）。 */
    private static final int EST_TASK_UNITS = 1000;

    private final ApiKeyAuthService authService;
    private final TaskService taskService;
    private final QuotaLedger quotaLedger;
    private final UsageMeter usageMeter;
    private final ModelPriceService modelPriceService;
    private final TaskAdapterRegistry adapterRegistry;

    /** 拒绝未建价模型（默认开·与 HTTP 强闸门 QuotaGateFilter 同口径）。 */
    @Value("${nexusai.billing.deny-unpriced-model:true}")
    private boolean denyUnpricedModel;

    /** sk- 鉴权（Authorization 头）。 */
    public NaiToken authenticate(String authHeader) {
        return authService.authenticate(authHeader);
    }

    /** 提交异步任务：按模型估价 pre-charge 冻结 → 持久化 SUBMITTED 任务（上游提交/轮询由 TaskAdapter 接手）。 */
    public NaiTask submit(NaiToken token, String modality, String action, String model, String body) {
        if (adapterRegistry.resolve(modality).isEmpty()) {
            throw GatewayException.of(GatewayErrorCode.NO_CHANNEL_AVAILABLE,
                    "异步任务上游适配器未配置: modality=" + modality);
        }
        UsdRate price = modelPriceService.priceFor(model);
        if (denyUnpricedModel && price.isUnknown()) {
            throw GatewayException.of(GatewayErrorCode.MODEL_PRICE_UNCONFIGURED,
                    "模型未在 nai_model 配置价格，拒绝计费: " + model);
        }
        // 🔴 image 模态按张计入 perImageFee（旧实现 images=0 漏算 → 按图计价模型 est≈0·闸门虚设·零余额可无限提交昂贵异步任务）
        int images = "image".equals(modality) ? 1 : 0;
        long est = usageMeter.microUsdFor(price, EST_TASK_UNITS, 0, 0, 0, images);
        boolean frozen = false;
        if (est > 0) {
            quotaLedger.freeze(token, est);
            frozen = true;
        }
        try {
            return taskService.submit(token, modality, action, model, body, est);
        } catch (RuntimeException e) {
            // 🔴 资金安全：持久化失败必须退还预冻结额度，否则 freeze 永久滞留（客户被超扣·与同步 relay 终态 refundAll 同口径）
            if (frozen) {
                try {
                    quotaLedger.settle(token, est);   // refund=est（全额未用·全退）
                } catch (RuntimeException refundErr) {
                    log.error("异步任务冻结回退失败 tokenId={} est={}（额度滞留·需对账）", token.getId(), est, refundErr);
                }
            }
            throw e;
        }
    }

    /** 查询任务：仅可查<b>本项目</b>记录（跨项目/不存在一律 404·不泄露他项目任务存在性）。 */
    public NaiTask query(NaiToken token, String taskId) {
        NaiTask t = taskService.get(taskId);
        if (t == null || token.getProjectCode() == null
                || !token.getProjectCode().equals(t.getProjectCode())) {
            throw GatewayException.of(GatewayErrorCode.MODEL_NOT_FOUND, "任务不存在: " + taskId);
        }
        return t;
    }
}
