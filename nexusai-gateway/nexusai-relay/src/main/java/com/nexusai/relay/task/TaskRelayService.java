package com.nexusai.relay.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.nexusai.billing.config.ModelPriceService;
import com.nexusai.billing.config.UsdRate;
import com.nexusai.billing.meter.UsageMeter;
import com.nexusai.billing.quota.QuotaLedger;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.common.util.IpMatcher;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.limit.RateLimiterService;
import com.nexusai.persistence.po.NaiTask;
import com.nexusai.persistence.po.NaiToken;
import com.nexusai.auth.ApiKeyAuthService;
import com.nexusai.task.TaskService;
import com.nexusai.task.spi.TaskAdapterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

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
    private final RateLimiterService rateLimiter;

    /** 拒绝未建价模型（默认开·与 HTTP 强闸门 QuotaGateFilter 同口径）。 */
    @Value("${nexusai.billing.deny-unpriced-model:true}")
    private boolean denyUnpricedModel;

    /** sk- 鉴权（Authorization 头）。 */
    public NaiToken authenticate(String authHeader) {
        return authService.authenticate(authHeader);
    }

    /**
     * 提交异步任务：入站准入（IP 白名单 / 模型权限 / Key RPM·与 RelayFilter 链同口径）→ 按模型估价 pre-charge 冻结
     * → 持久化 SUBMITTED 任务（上游提交/轮询由 TaskAdapter 接手）。任务端点不经 RelayFilter 链，故此处显式补齐准入。
     * 注：项目/工作区级 RPM+TPM 与入站并发槽尚未覆盖（需抽公共 admission guard 与 RelayFilter 共用）·属后续。
     */
    public NaiTask submit(NaiToken token, String modality, String action, String model, String body, String clientIp) {
        // 🔴 IP 白名单（nai_token.allow_ips·与 TokenAccessFilter 同口径）：被限 IP 的 Key 不得走任务口绕过来源管控
        if (!ipAllowed(token, clientIp)) {
            throw GatewayException.of(GatewayErrorCode.IP_NOT_ALLOWED, "该 Key 不允许来源 IP: " + clientIp);
        }
        // 🔴 模型权限白名单（nai_token.model_limits·与 ModelPermissionFilter/realtime admit 同口径）：
        // 否则被限模型的 Key 可走 /mj·/suno·/kling·/v1/tasks 直连昂贵模型（越权）。
        if (!modelAllowed(token, model)) {
            throw GatewayException.of(GatewayErrorCode.MODEL_NOT_PERMITTED, "该 Key 无权访问模型: " + model);
        }
        // 🔴 Key 级 RPM 硬闸门（nai_token.rpm_limit·与 RateLimitFilter 同口径·0=不限）：防被限速 Key 走任务口无限提交昂贵任务
        int keyRpm = token.getRpmLimit() == null ? 0 : token.getRpmLimit();
        if (!rateLimiter.tryAcquire("apikey", String.valueOf(token.getId()), keyRpm)) {
            throw GatewayException.of(GatewayErrorCode.RATE_LIMITED, "Key RPM 超限");
        }
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

    /** 模型权限白名单校验（nai_token.model_limits·空=不限；与 ModelPermissionFilter 同口径）。 */
    private static boolean modelAllowed(NaiToken token, String model) {
        String json = token.getModelLimits();
        if (json == null || json.isBlank()) {
            return true;
        }
        try {
            List<String> allowed = JsonUtils.parse(json, new TypeReference<List<String>>() { });
            return allowed == null || allowed.isEmpty() || allowed.contains(model);
        } catch (Exception e) {
            return true;
        }
    }

    /** 来源 IP 白名单校验（nai_token.allow_ips·空/未配=不限·解析失败 fail-open；与 TokenAccessFilter 同口径）。 */
    private static boolean ipAllowed(NaiToken token, String ip) {
        String raw = token.getAllowIps();
        if (raw == null || raw.isBlank() || ip == null || ip.isBlank()) {
            return true;
        }
        List<String> allowed = parseIps(raw);
        return allowed == null || allowed.isEmpty() || IpMatcher.inWhitelist(ip, allowed);
    }

    /** allow_ips 兼容 JSON 数组与逗号分隔两种存法（与 TokenAccessFilter.parseIps 同口径·解析失败回 null=fail-open）。 */
    private static List<String> parseIps(String raw) {
        String trimmed = raw.trim();
        try {
            if (trimmed.startsWith("[")) {
                return JsonUtils.parse(trimmed, new TypeReference<List<String>>() { });
            }
            return Arrays.stream(trimmed.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        } catch (Exception e) {
            return null;
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
