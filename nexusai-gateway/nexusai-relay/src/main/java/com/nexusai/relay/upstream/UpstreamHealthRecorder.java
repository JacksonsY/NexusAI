package com.nexusai.relay.upstream;

import com.nexusai.relay.context.RelayContext;
import com.nexusai.router.RouteResult;
import com.nexusai.router.health.ChannelHealthManager;
import com.nexusai.router.health.KeyHealthManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 上游健康记账（终态/重试时回写打分与熔断）：成功记延迟、429 记限流 + 自适应 RPM 学习、
 * 401/403 记 Key 封禁（鉴权失败是 Key 的问题）、5xx/网络记瞬时错误（渠道+Key 双维）。
 *
 * @author nexusai
 */
@Component
@RequiredArgsConstructor
public class UpstreamHealthRecorder {

    private final KeyHealthManager keyHealth;
    private final ChannelHealthManager channelHealth;

    public void recordSuccess(RelayContext ctx) {
        RouteResult r = ctx.getRoute();
        long latency = (System.nanoTime() - ctx.getStartNanos()) / 1_000_000L;
        keyHealth.recordSuccess(r.key().getId(), latency);
        channelHealth.recordSuccess(r.channel().getId(), latency);
    }

    public void recordFailure(RelayContext ctx, int sc, ClientResponse cr) {
        RouteResult r = ctx.getRoute();
        if (sc == 429) {
            Long retryAfter = parseRetryAfterMs(cr);
            // 自适应 RPM 学习：从上游 429 响应头解析其声明的 RPM 限额作为学习上限，激活 rpmLearned→打分降权闭环。
            keyHealth.recordRateLimited(r.key().getId(), retryAfter, parseObservedRpm(cr));
            channelHealth.recordRateLimited(r.channel().getId(), retryAfter);
        } else if (sc == 401 || sc == 403) {
            keyHealth.recordAuthBan(r.key().getId());   // Key 维度封禁（鉴权失败是 Key 的问题）
        } else if (sc >= 500) {
            keyHealth.recordTransientError(r.key().getId());
            channelHealth.recordTransientError(r.channel().getId());
        }
    }

    /** 透传阶段异常（连接/超时·无响应可解析）：渠道+Key 双维瞬时错误。 */
    public void recordTransient(RelayContext ctx) {
        RouteResult r = ctx.getRoute();
        keyHealth.recordTransientError(r.key().getId());
        channelHealth.recordTransientError(r.channel().getId());
    }

    private static Long parseRetryAfterMs(ClientResponse cr) {
        try {
            String ra = cr.headers().header("Retry-After").stream().findFirst().orElse(null);
            if (ra == null || ra.isBlank()) {
                return null;
            }
            ra = ra.trim();
            // 形式一：delta-seconds（纯数字·最常见）
            if (!ra.isEmpty() && ra.chars().allMatch(Character::isDigit)) {
                return Long.parseLong(ra) * 1000L;
            }
            // 形式二：HTTP-date（RFC 1123·如 "Wed, 21 Oct 2025 07:28:00 GMT"）→ 距现在毫秒（下限 0·过去时刻即可立刻重试）
            long deltaMs = Duration.between(ZonedDateTime.now(),
                    ZonedDateTime.parse(ra, DateTimeFormatter.RFC_1123_DATE_TIME)).toMillis();
            return Math.max(deltaMs, 0L);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析上游 429 响应里声明的每分钟请求限额（自适应 RPM 学习的观测上限）。
     * 兼容 OpenAI {@code x-ratelimit-limit-requests}、Anthropic {@code anthropic-ratelimit-requests-limit}、
     * 通用 {@code x-ratelimit-limit}；缺失/非数字返回 0（不学习）。
     */
    private static int parseObservedRpm(ClientResponse cr) {
        for (String h : new String[]{"x-ratelimit-limit-requests", "anthropic-ratelimit-requests-limit", "x-ratelimit-limit"}) {
            String v = cr.headers().header(h).stream().findFirst().orElse(null);
            if (v != null && !v.isBlank()) {
                try {
                    int rpm = Integer.parseInt(v.trim());
                    if (rpm > 0) {
                        return rpm;
                    }
                } catch (NumberFormatException ignore) {
                    // 下一个候选头
                }
            }
        }
        return 0;
    }
}
