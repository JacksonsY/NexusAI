package com.nexusai.persistence.log.ds;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 日志库独立连接配置：{@code nexusai.log.datasource.*}。
 * <p>{@code enabled=true} 时日志主表（nai_request_log）走<b>独立 DataSource</b>（与业务库物理分离）；
 * 缺省 {@code enabled=false} 时日志 mapper 回退主业务源（向后兼容·等于改造前行为）。
 *
 * @author nexusai
 */
@Data
@ConfigurationProperties(prefix = "nexusai.log.datasource")
public class LogDataSourceProperties {

    /** 是否启用独立日志库连接；false（默认）= 回退主业务源。 */
    private boolean enabled = false;

    private String url;
    private String username;
    private String password;
    private String driverClassName;

    /** 独立连接池大小（日志写多读少，可小于业务源）。 */
    private int poolSize = 8;

    /** 日志库方言（mysql/postgresql）——独立于业务 {@code nexusai.db.type}，决定日志分页 SQL 方言。 */
    private String dialect = "mysql";
}
