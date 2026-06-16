package com.nexusai.relay.config;

import com.nexusai.protocol.transform.CanonicalConverter;
import com.nexusai.provider.anthropic.AnthropicProvider;
import com.nexusai.provider.azure.AzureOpenAiProvider;
import com.nexusai.provider.bedrock.BedrockProvider;
import com.nexusai.provider.gemini.GeminiProvider;
import com.nexusai.provider.openai.OpenAiCompatibleProvider;
import com.nexusai.provider.spi.Provider;
import com.nexusai.provider.spi.ProviderFactory;
import com.nexusai.provider.vertex.VertexProvider;
import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.List;

/**
 * relay 装配：WebClient（上游透传·连接池+超时+放大内存缓冲）+ Provider SPI 收集 + ProviderFactory
 * + Canonical 互转中枢（纯净 protocol/provider 模块经此 @Bean 接入 Spring）。
 *
 * @author nexusai
 */
@Configuration(proxyBeanMethods = false)
public class RelayConfig {

    /**
     * 上游透传 WebClient（评审 H2：原仅设内存缓冲、无任何连接池/超时配置·SSE 半开连接无限占槽、并发硬顶默认 500）。
     * <p>配连接池（高水位 maxConnections + pendingAcquire 限额/超时·防获取连接无限排队）+ 空闲/存活驱逐
     * （半开/慢吐连接到期回收）+ 连接超时；<b>不设全局 responseTimeout</b>——长流式响应合法可达数分钟，
     * 总时长超时会误杀正常长流，改由空闲驱逐 + 客户端取消（doFinally 释槽）兜底。
     */
    @Bean
    public WebClient upstreamWebClient(WebClient.Builder builder) {
        ConnectionProvider pool = ConnectionProvider.builder("upstream")
                .maxConnections(2000)                          // 8核8G 评估高水位（替代默认 500）
                .pendingAcquireMaxCount(4000)                  // 获取连接排队上限（满即快速失败而非无限堆积）
                .pendingAcquireTimeout(Duration.ofSeconds(10)) // 排队等待上限
                .maxIdleTime(Duration.ofSeconds(60))           // 空闲连接驱逐（回收半开/慢吐）
                .maxLifeTime(Duration.ofMinutes(10))           // 连接最大存活
                .evictInBackground(Duration.ofSeconds(120))    // 后台周期性驱逐
                .build();
        HttpClient httpClient = HttpClient.create(pool)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000);
        return builder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    /** Canonical 跨协议互转中枢（纯净 protocol 模块·无 Spring 依赖，此处注册为 bean）。 */
    @Bean
    public CanonicalConverter canonicalConverter() {
        return new CanonicalConverter();
    }

    // ===== Provider SPI（纯净 provider 模块·无 Spring 依赖，逐个 @Bean 并入 List<Provider>）=====

    /** openai-compatible：档①绝大多数上游（DeepSeek/Kimi/通义等 OpenAI 协议家族）+ 工厂兜底。 */
    @Bean
    public OpenAiCompatibleProvider openAiCompatibleProvider() {
        return new OpenAiCompatibleProvider();
    }

    @Bean
    public AnthropicProvider anthropicProvider() {
        return new AnthropicProvider();
    }

    @Bean
    public GeminiProvider geminiProvider() {
        return new GeminiProvider();
    }

    @Bean
    public AzureOpenAiProvider azureOpenAiProvider() {
        return new AzureOpenAiProvider();
    }

    @Bean
    public BedrockProvider bedrockProvider() {
        return new BedrockProvider();
    }

    @Bean
    public VertexProvider vertexProvider() {
        return new VertexProvider();
    }

    /** Provider 列表由 Spring 自动收集（SPI），工厂精确匹配 providerKey、未命中回退 openai-compatible。 */
    @Bean
    public ProviderFactory providerFactory(List<Provider> providers) {
        return new ProviderFactory(providers);
    }
}
