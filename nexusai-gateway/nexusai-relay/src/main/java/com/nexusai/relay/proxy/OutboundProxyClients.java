package com.nexusai.relay.proxy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.nexusai.billing.crypto.CredentialCipher;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.persistence.mapper.NaiProxyNodeMapper;
import com.nexusai.persistence.mapper.NaiSystemConfigMapper;
import com.nexusai.persistence.po.NaiProxyNode;
import com.nexusai.persistence.po.NaiSystemConfig;
import io.netty.channel.ChannelOption;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.ProxyProvider;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 出站代理 WebClient 池（渠道级代理 + 全局代理节点）：
 * 渠道 setting.proxyNodeId > 全局 system_proxy_node_id > 直连；
 * 每代理节点缓存独立 WebClient（reactor-netty ProxyProvider·HTTP/SOCKS5）；
 * 经代理请求计数（成功/失败 LongAdder）由守护线程每 60s 落库 nai_proxy_node。
 *
 * @author nexusai
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboundProxyClients {

    private static final String CFG_SYSTEM_PROXY = "system_proxy_node_id";

    private final NaiProxyNodeMapper nodeMapper;
    private final NaiSystemConfigMapper systemConfigMapper;
    private final CredentialCipher cipher;
    private final WebClient upstreamWebClient;

    /** nodeId → 经代理 WebClient（节点配置 30s 刷新重建） */
    private final LoadingCache<Long, Optional<WebClient>> clients = Caffeine.newBuilder()
            .maximumSize(128)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build(this::buildClient);

    /** 全局默认代理节点 id（5s 挡读） */
    private final LoadingCache<String, Optional<Long>> systemProxy = Caffeine.newBuilder()
            .maximumSize(1)
            .expireAfterWrite(Duration.ofSeconds(5))
            .build(k -> loadSystemProxyId());

    /** 共享出站代理连接池：被所有代理 WebClient 复用（reactor-netty 按远端地址分桶），避免每次重建 client 泄漏连接池/事件循环线程。 */
    private final ConnectionProvider proxyConnectionProvider = ConnectionProvider.builder("upstream-proxy")
            .maxConnections(500)
            .pendingAcquireTimeout(Duration.ofSeconds(10))
            .maxIdleTime(Duration.ofSeconds(60))
            .maxLifeTime(Duration.ofMinutes(10))
            .build();

    private final Map<Long, LongAdder> totalCounters = new ConcurrentHashMap<>();
    private final Map<Long, LongAdder> failedCounters = new ConcurrentHashMap<>();
    private volatile Thread flusher;
    private volatile boolean running = true;

    /**
     * 解析本次透传应使用的 WebClient + 代理节点 id（null=直连）。
     *
     * @param channelSettingJson 渠道 setting JSON（可含 proxyNodeId）
     */
    public Resolved resolve(String channelSettingJson) {
        Long nodeId = channelProxyNodeId(channelSettingJson);
        if (nodeId == null) {
            nodeId = systemProxy.get("").orElse(null);
        }
        if (nodeId == null) {
            return new Resolved(upstreamWebClient, null);
        }
        WebClient client = clients.get(nodeId).orElse(null);
        if (client == null) {
            // 节点不存在/构建失败 → 直连兜底（不让代理配置坏掉主链）
            return new Resolved(upstreamWebClient, null);
        }
        ensureFlusher();
        return new Resolved(client, nodeId);
    }

    /** 透传结束计数（仅经代理的请求）。 */
    public void record(Long nodeId, boolean ok) {
        if (nodeId == null) {
            return;
        }
        totalCounters.computeIfAbsent(nodeId, k -> new LongAdder()).increment();
        if (!ok) {
            failedCounters.computeIfAbsent(nodeId, k -> new LongAdder()).increment();
        }
    }

    private static Long channelProxyNodeId(String settingJson) {
        if (settingJson == null || settingJson.isBlank()) {
            return null;
        }
        try {
            JsonNode n = JsonUtils.readTree(settingJson).path("proxyNodeId");
            if (n.isNumber()) {
                return n.asLong();
            }
            if (n.isTextual() && !n.asText().isBlank()) {
                return Long.parseLong(n.asText());
            }
        } catch (Exception ignored) {
            // setting 非法时按直连处理
        }
        return null;
    }

    private Optional<Long> loadSystemProxyId() {
        NaiSystemConfig row = systemConfigMapper.selectOne(new LambdaQueryWrapper<NaiSystemConfig>()
                .eq(NaiSystemConfig::getCfgKey, CFG_SYSTEM_PROXY));
        if (row == null || row.getCfgValue() == null || row.getCfgValue().isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(row.getCfgValue().replace("\"", "").trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private Optional<WebClient> buildClient(Long nodeId) {
        NaiProxyNode node = nodeMapper.selectById(nodeId);
        if (node == null) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(node.getProxyUrl());
            ProxyProvider.Proxy type = uri.getScheme().startsWith("socks")
                    ? ProxyProvider.Proxy.SOCKS5 : ProxyProvider.Proxy.HTTP;
            int port = uri.getPort() > 0 ? uri.getPort() : (type == ProxyProvider.Proxy.SOCKS5 ? 1080 : 80);
            String password = node.getPasswordEnc() == null || node.getPasswordEnc().isBlank()
                    ? null : cipher.decrypt(node.getPasswordEnc());
            HttpClient httpClient = HttpClient.create(proxyConnectionProvider)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                    .proxy(spec -> {
                        ProxyProvider.Builder b = spec.type(type).host(uri.getHost()).port(port);
                        if (node.getUsername() != null && !node.getUsername().isBlank()) {
                            b.username(node.getUsername()).password(u -> password == null ? "" : password);
                        }
                    });
            return Optional.of(WebClient.builder()
                    .clientConnector(new ReactorClientHttpConnector(httpClient))
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                    .build());
        } catch (Exception e) {
            log.warn("代理节点 {} WebClient 构建失败（直连兜底）: {}", nodeId, e.toString());
            return Optional.empty();
        }
    }

    private void ensureFlusher() {
        if (flusher != null) {
            return;
        }
        synchronized (this) {
            if (flusher != null) {
                return;
            }
            flusher = new Thread(() -> {
                while (running) {
                    try {
                        Thread.sleep(60_000L);
                        flush();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Exception e) {
                        log.debug("代理计数落库失败（下轮重试）: {}", e.getMessage());
                    }
                }
            }, "nai-proxy-counter-flush");
            flusher.setDaemon(true);
            flusher.start();
        }
    }

    void flush() {
        for (Map.Entry<Long, LongAdder> entry : totalCounters.entrySet()) {
            long total = entry.getValue().sumThenReset();
            long failed = failedCounters.getOrDefault(entry.getKey(), new LongAdder()).sumThenReset();
            if (total == 0 && failed == 0) {
                continue;
            }
            NaiProxyNode node = nodeMapper.selectById(entry.getKey());
            if (node == null) {
                continue;
            }
            node.setTotalRequests((node.getTotalRequests() == null ? 0 : node.getTotalRequests()) + total);
            node.setFailedRequests((node.getFailedRequests() == null ? 0 : node.getFailedRequests()) + failed);
            node.setUpdateDt(LocalDateTime.now());
            nodeMapper.updateById(node);
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (flusher != null) {
            flusher.interrupt();
        }
        flush();
        proxyConnectionProvider.dispose();   // 释放共享代理连接池（事件循环/连接资源）
    }

    /** 解析结果：本次透传用的 client + 代理节点 id（null=直连）。 */
    public record Resolved(WebClient client, Long proxyNodeId) {
    }
}
