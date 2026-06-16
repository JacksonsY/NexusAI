package com.nexusai.control.domain.routing.model.service;

import lombok.extern.slf4j.Slf4j;
import io.netty.channel.ChannelOption;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * models.dev 模型目录代理（创建模型对话框左侧目录树）：
 * 后端代理拉 <a href="https://models.dev/api.json">models.dev/api.json</a>（解决浏览器跨域），
 * 原始 JSON 直接透传（前端自己解析结构），进程内缓存 1 小时；上游失败回最近一次成功值（目录树降级为空仍可手填）。
 *
 * @author nexusai
 */
@Slf4j
@Service
public class ModelsDevCatalogService {

    private static final String MODELS_DEV_URL = "https://models.dev/api.json";
    private static final Duration CACHE_TTL = Duration.ofHours(1);
    /** api.json 约 2MB，默认 256KB 内存上限不够 */
    private static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

    private final WebClient client;
    private final AtomicReference<String> cached = new AtomicReference<>();
    private final AtomicLong cachedAt = new AtomicLong();

    public ModelsDevCatalogService() {
        // proxyWithSystemProperties：读标准 -Dhttps.proxyHost/-Dhttps.proxyPort（生产直连不受影响）；
        // api.json ~2MB 且源站对部分网络较慢，响应超时放宽到 30s（1h 缓存 + 失败回旧值兜底）
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .proxyWithSystemProperties()
                .responseTimeout(Duration.ofSeconds(30));
        this.client = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(http))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_BODY_BYTES))
                .build();
    }

    /** 目录 JSON（阻塞·控制器经 boundedElastic 包裹）；缓存命中直接回，miss 拉上游，失败回旧值/空对象。 */
    public String catalogJson() {
        String value = cached.get();
        if (value != null && System.currentTimeMillis() - cachedAt.get() < CACHE_TTL.toMillis()) {
            return value;
        }
        try {
            String fresh = client.get().uri(MODELS_DEV_URL)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(35));
            if (fresh != null && !fresh.isBlank()) {
                cached.set(fresh);
                cachedAt.set(System.currentTimeMillis());
                return fresh;
            }
        } catch (Exception e) {
            log.warn("models.dev 目录拉取失败，回退缓存: {}", e.getMessage());
        }
        return value != null ? value : "{}";
    }
}
