package com.nexusai.persistence.log.ds;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 日志按时间分表配置：{@code nexusai.log.sharding.*}。
 *
 * <p><b>当前状态（2026-06-09 接入·MySQL）</b>：写入路由 + 自动建表 + 当前窗查询<b>已接入</b>——由
 * {@code MyBatisPlusConfig} 的 DynamicTableNameInnerInterceptor 把 {@code nai_request_log} 读写一并重写到
 * {@code nai_request_log_<period>}（day/week/month/year），首次命中惰性 {@code CREATE TABLE IF NOT EXISTS ... LIKE} 自动建表。
 * <b>剩余</b>：跨期历史聚合（查上月等）多分片合并属查询语义冲突的独立增量（默认 month 粒度下 1h/24h/7d 近窗多落当前月分片故正常）；
 * PG/ClickHouse 分表 LIKE 语法不同留后续。落地见 docs/后端目录架构规范.md「日志分表」节。
 *
 * @author nexusai
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "nexusai.log.sharding")
public class LogShardingProperties {

    /** 是否启用按时间滚动分表（MySQL·写入路由+自动建表+当前窗查询已接入·见类注释）。 */
    private boolean enable = false;

    /** 分表粒度：day/week/month/year。 */
    private String mode = "month";

    @PostConstruct
    public void info() {
        if (enable) {
            log.info("nexusai.log.sharding.enable=true（mode={}）：nai_request_log 滚动分表已接入（MySQL·读写重写到当前周期分片+自动建表）。"
                    + "跨期历史聚合多分片合并属独立增量（近窗查询正常）。", mode);
        }
    }
}
