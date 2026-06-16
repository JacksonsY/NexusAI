package com.nexusai.client;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 控制面 Client 配置（前缀 {@code nexusai.client}）。
 * <p><b>配置 {@code token} 即激活</b> {@link NexusAiAdminClient} 自动装配（未配则不注册 bean·业务面不集成网关时无副作用）。
 * <pre>
 * nexusai:
 *   client:
 *     project-code: my-saas        # nai_project.project_code（标识本接入方·信息性）
 *     token: ${NEXUSAI_PROJECT_TOKEN}  # nai_project.token（M2M 认证令牌·必填）
 *     server:
 *       host: nexusai-gateway          # 网关地址（集群则配域名）
 *       port: 8600
 *       https: false
 * </pre>
 *
 * @author nexusai
 */
@Data
@ConfigurationProperties(prefix = "nexusai.client")
public class NexusAiClientProperties {

    /** 项目稳定标识（对应 nai_project.project_code·信息性·便于运维识别本接入方）。 */
    private String projectCode;

    /** M2M 认证令牌（对应 nai_project.token·经 token-header 传递·配置此项即激活 SDK·绝不硬编码到代码）。 */
    @ToString.Exclude  // 令牌不进 toString/日志（纵深防御）
    private String token;

    /** 令牌请求头名（须与网关 sa-token.token-name 一致）。 */
    private String tokenHeader = "NexusAI-Admin-Token";

    /** 网关 Server 连接。 */
    private Server server = new Server();

    /** 连接超时。 */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /** 读取超时（控制面均为快操作·非长流式）。 */
    private Duration readTimeout = Duration.ofSeconds(10);

    /** 据 server 段拼出基址 {@code http(s)://host:port}。 */
    public String resolveBaseUrl() {
        return (server.https ? "https" : "http") + "://" + server.host + ":" + server.port;
    }

    @Data
    public static class Server {
        /** 网关地址（集群则配域名）。 */
        private String host = "127.0.0.1";
        /** 网关端口（对应网关 server.port）。 */
        private int port = 8600;
        /** 是否 https。 */
        private boolean https = false;
    }
}
