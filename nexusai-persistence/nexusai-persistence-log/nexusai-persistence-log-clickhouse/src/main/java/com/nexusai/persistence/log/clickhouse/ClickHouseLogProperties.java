package com.nexusai.persistence.log.clickhouse;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ClickHouse 日志存储配置（前缀 {@code nexusai.log.clickhouse}）。
 * <p>仅当 {@code nexusai.log.store=clickhouse} 时生效；driver-class-name 指向运行时加入的 ClickHouse JDBC 驱动。
 *
 * @author nexusai
 */
@Data
@ConfigurationProperties(prefix = "nexusai.log.clickhouse")
public class ClickHouseLogProperties {

    /** JDBC URL，如 {@code jdbc:ch://clickhouse:8123/nexusai}。 */
    private String url = "jdbc:ch://127.0.0.1:8123/nexusai";

    /** JDBC 驱动类名（运行时加入 clickhouse-jdbc 后可用）。 */
    private String driverClassName = "com.clickhouse.jdbc.ClickHouseDriver";

    private String username = "default";

    @ToString.Exclude
    private String password = "";

    /** 目标表名（MyBatis-Plus 经 @TableName 固定为 nai_request_log·此项保留作信息）。 */
    private String table = "nai_request_log";

    /** 连接池大小。 */
    private int poolSize = 4;
}
