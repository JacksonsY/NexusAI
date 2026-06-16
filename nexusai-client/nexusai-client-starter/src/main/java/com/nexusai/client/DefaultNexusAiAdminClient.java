package com.nexusai.client;

import com.nexusai.client.model.ApiResult;
import com.nexusai.contract.key.KeySyncRequest;
import com.nexusai.contract.quota.BalanceView;
import com.nexusai.contract.quota.QuotaOpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.function.Supplier;

/**
 * {@link NexusAiAdminClient} 默认实现（Spring {@link RestClient} 同步阻塞）。
 * <p>RestClient 已在自动装配中预置 base-url + NexusAI-Admin-Token 默认头 + 连接/读取超时。
 * 统一解包 {@link ApiResult} 信封：非 200 / HTTP 错误 / 传输失败均转 {@link NexusAiClientException}。
 *
 * @author nexusai
 */
public class DefaultNexusAiAdminClient implements NexusAiAdminClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultNexusAiAdminClient.class);

    /** 传输层失败（连接超时/拒绝/解码失败/无响应体）统一业务码——区别于网关真实返回的 HTTP/业务码。 */
    private static final int TRANSPORT_FAILURE_CODE = -1;
    /** 网关错误响应体最大保留长度（防 HTML/长栈污染业务面日志；完整体仍在 cause 链）。 */
    private static final int ERROR_BODY_MAX_LEN = 512;

    private static final ParameterizedTypeReference<ApiResult<Long>> RESULT_LONG =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResult<BigDecimal>> RESULT_BIGDECIMAL =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResult<Void>> RESULT_VOID =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResult<BalanceView>> RESULT_BALANCE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public DefaultNexusAiAdminClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public Long syncKey(KeySyncRequest request) {
        Long tokenId = call("syncKey", () -> restClient.put()
                .uri("/admin/keys")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(RESULT_LONG));
        // 评审 LOW：syncKey 的 tokenId 是承载性返回（后续 Quota 操作依赖），成功但缺 data 即接口违例，就地 fail 而非延迟到调用点
        if (tokenId == null) {
            throw new NexusAiClientException(TRANSPORT_FAILURE_CODE, "网关控制面 syncKey 成功但未返回 tokenId（契约违例）");
        }
        return tokenId;
    }

    @Override
    public void revokeKey(Long tokenId) {
        call("revokeKey", () -> restClient.post()
                .uri("/admin/keys/{id}/revoke", tokenId)
                .retrieve()
                .body(RESULT_VOID));
    }

    @Override
    public BigDecimal quotaOp(QuotaOpRequest request) {
        BigDecimal balance = call("quotaOp", () -> restClient.post()
                .uri("/admin/quota/op")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(RESULT_BIGDECIMAL));
        return balance == null ? BigDecimal.ZERO : balance;
    }

    @Override
    public BigDecimal queryBalance(String projectCode) {
        BalanceView view = call("queryBalance", () -> restClient.get()
                .uri("/admin/quota/{projectCode}/balance", projectCode)
                .retrieve()
                .body(RESULT_BALANCE));
        return unwrapBalance(view);
    }

    @Override
    public BigDecimal queryUserBalance(String projectCode, String externalWorkspaceId) {
        BalanceView view = call("queryUserBalance", () -> restClient.get()
                .uri("/admin/quota/{projectCode}/balance?externalWorkspaceId={uid}", projectCode, externalWorkspaceId)
                .retrieve()
                .body(RESULT_BALANCE));
        return unwrapBalance(view);
    }

    /** 解包余额视图：不限额返回 null（哨兵值不外泄·接口约定）；balance 为 USD 字符串。 */
    private static BigDecimal unwrapBalance(BalanceView view) {
        if (view == null) {
            return BigDecimal.ZERO;
        }
        if (view.unlimited()) {
            return null;
        }
        String balance = view.balance();
        return balance != null && !balance.isBlank() ? new BigDecimal(balance) : BigDecimal.ZERO;
    }

    /** 执行调用 + 解包信封：传输/HTTP 错误转 NexusAiClientException，业务非 200 抛错，成功返回 data。 */
    private <T> T call(String op, Supplier<ApiResult<T>> supplier) {
        ApiResult<T> result;
        try {
            result = supplier.get();
        } catch (RestClientResponseException e) {
            // 网关返回 4xx/5xx（如 401 令牌失效、500 业务异常）。评审 LOW：响应体截断，防 HTML/长栈污染业务面日志（完整体仍在 cause 链）
            throw new NexusAiClientException(e.getStatusCode().value(),
                    "网关控制面 " + op + " 失败 HTTP " + e.getStatusCode().value() + ": "
                            + truncate(e.getResponseBodyAsString(), ERROR_BODY_MAX_LEN), e);
        } catch (RestClientException e) {
            // 连接超时/拒绝/解码失败
            throw new NexusAiClientException(TRANSPORT_FAILURE_CODE, "网关控制面 " + op + " 连接失败: " + e.getMessage(), e);
        }
        if (result == null) {
            throw new NexusAiClientException(TRANSPORT_FAILURE_CODE, "网关控制面 " + op + " 无响应体");
        }
        if (!result.ok()) {
            throw new NexusAiClientException(result.code() == null ? TRANSPORT_FAILURE_CODE : result.code(),
                    "网关控制面 " + op + " 业务失败: " + result.msg());
        }
        if (log.isDebugEnabled()) {
            log.debug("网关控制面 {} 成功 data={}", op, result.data());
        }
        return result.data();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…(truncated " + (s.length() - max) + ")";
    }
}
