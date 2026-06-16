package com.nexusai.control.domain.routing.channel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nexusai.billing.crypto.CredentialCipher;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.control.domain.routing.channel.vo.ChannelBalanceVo;
import com.nexusai.persistence.mapper.NaiChannelKeyMapper;
import com.nexusai.persistence.mapper.NaiChannelMapper;
import com.nexusai.persistence.po.NaiChannel;
import com.nexusai.persistence.po.NaiChannelKey;
import io.netty.channel.ChannelOption;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

/**
 * 渠道余额监控（提供商页「余额监控」列）：
 * 取渠道 baseUrl + 首条启用 Key，按 OpenAI 兼容计费端点查上游剩余额度——
 * {@code /dashboard/billing/subscription}（总额度 hard_limit_usd）+
 * {@code /dashboard/billing/usage}（本期已用 total_usage 美分）→ 余额 = 总额 − 已用。
 * 中转站普遍兼容该口径；不支持的上游返回错误态（前端红字展示）。
 * <p>结果 Caffeine 缓存 5 分钟（列表页逐渠道并发查·防每次刷新击穿上游）；{@code force} 绕过缓存。
 *
 * @author nexusai
 */
@Service
public class ChannelBalanceService {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(10);
    /** 余额结果缓存 TTL（成功结果·列表页逐渠道并发查·防每次刷新击穿上游） */
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final int CACHE_MAX_SIZE = 2_000;
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final NaiChannelMapper channelMapper;
    private final NaiChannelKeyMapper keyMapper;
    private final CredentialCipher cipher;
    private final WebClient client;

    /** 余额结果缓存（channelId → 结果·成功缓存 {@link #CACHE_TTL}）。 */
    private final Cache<Long, ChannelBalanceVo> cache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .maximumSize(CACHE_MAX_SIZE)
            .build();

    public ChannelBalanceService(NaiChannelMapper channelMapper, NaiChannelKeyMapper keyMapper,
                                 CredentialCipher cipher) {
        this.channelMapper = channelMapper;
        this.keyMapper = keyMapper;
        this.cipher = cipher;
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(RESPONSE_TIMEOUT);
        this.client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(http)).build();
    }

    /** 查询渠道余额（阻塞·控制器经 boundedElastic 包裹；force 绕过缓存）。 */
    public ChannelBalanceVo balance(Long channelId, boolean force) {
        if (!force) {
            ChannelBalanceVo cached = cache.getIfPresent(channelId);
            if (cached != null) {
                return cached;
            }
        }
        ChannelBalanceVo result = query(channelId);
        if (result.ok()) {
            cache.put(channelId, result);
        }
        return result;
    }

    private ChannelBalanceVo query(Long channelId) {
        NaiChannel ch = channelMapper.selectById(channelId);
        if (ch == null || !StringUtils.hasText(ch.getBaseUrl())) {
            return ChannelBalanceVo.fail("渠道不存在或未配置 baseUrl");
        }
        NaiChannelKey key = keyMapper.selectOne(new LambdaQueryWrapper<NaiChannelKey>()
                .eq(NaiChannelKey::getChannelId, channelId)
                .eq(NaiChannelKey::getStatus, 1)
                .orderByAsc(NaiChannelKey::getId)
                .last("limit 1"));
        if (key == null) {
            return ChannelBalanceVo.fail("未配置 API Key");
        }
        String plain;
        try {
            plain = cipher.decrypt(key.getCredential());
        } catch (Exception e) {
            return ChannelBalanceVo.fail("凭证解密失败");
        }

        String base = ch.getBaseUrl().replaceAll("/+$", "");
        try {
            Map<String, Object> subscription = getJson(base + "/dashboard/billing/subscription", plain);
            BigDecimal limit = asBigDecimal(subscription.get("hard_limit_usd"));
            // usage 窗口：当月 1 日 → 明日（total_usage 单位为美分）
            LocalDate now = LocalDate.now();
            String usageUrl = base + "/dashboard/billing/usage?start_date=" + now.withDayOfMonth(1)
                    + "&end_date=" + now.plusDays(1);
            Map<String, Object> usage = getJson(usageUrl, plain);
            BigDecimal used = asBigDecimal(usage.get("total_usage"))
                    .movePointLeft(2);   // total_usage 单位为美分 → USD

            return ChannelBalanceVo.ok(round2(limit), round2(used), round2(limit.subtract(used)));
        } catch (Exception e) {
            return ChannelBalanceVo.fail(shortMessage(e));
        }
    }

    private Map<String, Object> getJson(String url, String bearer) {
        String body = client.get().uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .retrieve()
                .bodyToMono(String.class)
                .block(BLOCK_TIMEOUT);
        Map<String, Object> parsed = JsonUtils.parse(body, JSON_OBJECT);
        if (parsed == null) {
            throw new IllegalStateException("上游返回非 JSON");
        }
        return parsed;
    }

    private static BigDecimal asBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal round2(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String shortMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "上游不支持余额查询";
        }
        if (message.contains("401") || message.contains("403")) {
            return "鉴权失败（Key 无效？）";
        }
        if (message.contains("404")) {
            return "上游不支持余额查询";
        }
        return message.length() > 60 ? message.substring(0, 60) : message;
    }
}
