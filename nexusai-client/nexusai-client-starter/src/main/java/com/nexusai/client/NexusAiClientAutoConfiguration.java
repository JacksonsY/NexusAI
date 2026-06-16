package com.nexusai.client;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * 控制面 Client 自动装配。<b>配置 {@code nexusai.client.token} 即激活</b>（业务面不集成网关时不注册任何 bean）。
 * <p>装配一个预置 base-url（由 server.host:port 拼出）+ NexusAI-Admin-Token 默认头 + 连接/读取超时的
 * {@link RestClient}，注入 {@link DefaultNexusAiAdminClient}。
 *
 * @author nexusai
 */
@AutoConfiguration
@EnableConfigurationProperties(NexusAiClientProperties.class)
@ConditionalOnProperty(prefix = "nexusai.client", name = "token")
public class NexusAiClientAutoConfiguration {

    /** 控制面专用 RestClient bean 名（隔离命名·避免与业务面其它 RestClient 冲突）。 */
    private static final String ADMIN_REST_CLIENT_BEAN = "nexusAiAdminRestClient";

    /** 控制面专用 RestClient（与业务面其它 RestClient 隔离·避免污染默认 builder）。 */
    @Bean(ADMIN_REST_CLIENT_BEAN)
    @ConditionalOnMissingBean(name = ADMIN_REST_CLIENT_BEAN)
    public RestClient nexusAiAdminRestClient(NexusAiClientProperties props) {
        // @ConditionalOnProperty(token) 对「存在但空串」也会激活，此处启动期 fail-fast（而非运行期传输失败）
        if (!StringUtils.hasText(props.getToken())) {
            throw new IllegalStateException("nexusai.client.token 为空（控制面 M2M 项目令牌必填）");
        }
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(props.getConnectTimeout())
                .withReadTimeout(props.getReadTimeout());
        ClientHttpRequestFactory factory = ClientHttpRequestFactoryBuilder.detect().build(settings);
        return RestClient.builder()
                .baseUrl(StringUtils.trimTrailingCharacter(props.resolveBaseUrl(), '/'))
                .defaultHeader(props.getTokenHeader(), props.getToken())
                .requestFactory(factory)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public NexusAiAdminClient nexusAiAdminClient(RestClient nexusAiAdminRestClient) {
        return new DefaultNexusAiAdminClient(nexusAiAdminRestClient);
    }
}
