package com.nexusai.persistence.log.body;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 请求体分离配置：{@code nexusai.log.body.*}。
 *
 * @author nexusai
 */
@Data
@ConfigurationProperties(prefix = "nexusai.log.body")
public class RequestBodyProperties {

    /** 正文存储档：hash（默认·不存只 sha256）/ rdb（独立库 nai_request_body）/ oss（对象存储·P2）。 */
    private String store = "hash";

    /** 是否采集正文（全局开关）；可被 token/渠道覆盖（集成点）。 */
    private boolean collect = false;

    /** 落库前 gzip 压缩正文。 */
    private boolean gzip = true;

    /** rdb 档：请求体独立库连接（第三个独立 DSN·与业务库/日志库均分离）。 */
    private DataSource datasource = new DataSource();

    @Data
    public static class DataSource {
        private String url;
        private String username;
        private String password;
        private String driverClassName;
        private int poolSize = 4;
    }
}
