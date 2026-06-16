package com.nexusai.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DynamicTableNameInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MyBatis-Plus 配置：分页拦截器 + 自动填充 create_dt/update_dt（BasePO·审计字段统一填充）
 * + 日志按时间滚动分表（{@code nexusai.log.sharding}·MySQL）。
 * <p><b>多方言</b>：分页 {@link DbType} 由 {@code nexusai.db.type} 决定（mysql 默认 / postgresql）。
 * <p><b>日志滚动分表</b>（{@code nexusai.log.sharding.enable=true} 且 MySQL）：{@link DynamicTableNameInnerInterceptor}
 * 把 {@code nai_request_log} 的<b>读写一并</b>重写到当前周期分片 {@code nai_request_log_<period>}（day/week/month/year），
 * 首次命中惰性 {@code CREATE TABLE IF NOT EXISTS ... LIKE} 自动建表。写入路由 + 自动建表 + <b>当前窗查询</b>已接入；
 * 跨期历史聚合（如查上月）属查询语义冲突的独立增量（默认 month 粒度下 1h/24h/7d 近窗多落当前月分片故正常）。
 *
 * @author nexusai
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class MyBatisPlusConfig {

    /** 日志基表名（滚动分表的逻辑基表·分片表以此为模板 CREATE ... LIKE）。 */
    private static final String LOG_BASE_TABLE = "nai_request_log";
    /** 日志分片表前缀（实际分片为 {@code nai_request_log_<period>}）。 */
    private static final String LOG_SHARD_PREFIX = LOG_BASE_TABLE + "_";
    /** 默认分表粒度（未配置或非法时回退）。 */
    private static final String DEFAULT_SHARD_MODE = "month";
    /** 单页记录上限（防恶意大分页拖垮库）。 */
    private static final long MAX_PAGE_SIZE = 500L;
    /** 审计字段：创建时间（BasePO 属性名·自动填充用）。 */
    private static final String FIELD_CREATE_DT = "createDt";
    /** 审计字段：更新时间（BasePO 属性名·自动填充用）。 */
    private static final String FIELD_UPDATE_DT = "updateDt";

    private final DbType dbType;
    private final boolean shardingEnabled;
    private final String shardMode;
    private final JdbcTemplate jdbc;
    /** 已确保存在的分片表（避免每次查询都跑 DDL） */
    private final Set<String> ensuredShards = ConcurrentHashMap.newKeySet();

    public MyBatisPlusConfig(@Value("${nexusai.db.type:mysql}") String dbType,
                             @Value("${nexusai.log.sharding.enable:false}") boolean shardingEnabled,
                             @Value("${nexusai.log.sharding.mode:month}") String shardMode,
                             DataSource dataSource) {
        this.dbType = resolveDbType(dbType);
        this.shardingEnabled = shardingEnabled;
        this.shardMode = shardMode == null ? DEFAULT_SHARD_MODE : shardMode.trim().toLowerCase();
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** mysql（默认）/ postgresql｜postgres｜pg → POSTGRE_SQL；其余回退 MySQL。 */
    private static DbType resolveDbType(String type) {
        String t = type == null ? "" : type.trim().toLowerCase();
        return switch (t) {
            case "postgresql", "postgres", "pg" -> DbType.POSTGRE_SQL;
            default -> DbType.MYSQL;
        };
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 动态表名必须在分页之前（否则 count SQL 表名未重写）
        if (shardingEnabled && dbType == DbType.MYSQL) {
            DynamicTableNameInnerInterceptor dyn = new DynamicTableNameInnerInterceptor();
            dyn.setTableNameHandler((sql, tableName) -> {
                if (LOG_BASE_TABLE.equals(tableName)) {
                    String shard = LOG_SHARD_PREFIX + currentSuffix();
                    ensureShard(shard);
                    return shard;
                }
                return tableName;
            });
            interceptor.addInnerInterceptor(dyn);
            log.info("日志滚动分表已启用（MySQL·mode={}）：{} → {}<period>", shardMode, LOG_BASE_TABLE, LOG_SHARD_PREFIX);
        }
        PaginationInnerInterceptor page = new PaginationInnerInterceptor(dbType);
        page.setMaxLimit(MAX_PAGE_SIZE);   // 单页上限，防恶意大分页
        interceptor.addInnerInterceptor(page);
        return interceptor;
    }

    /** 当前周期分片后缀（day=yyyyMMdd / week=yyyy'W'ww / month=yyyyMM / year=yyyy）。 */
    private String currentSuffix() {
        LocalDate d = LocalDate.now();
        return switch (shardMode) {
            case "day" -> d.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            case "week" -> d.getYear() + "W" + String.format("%02d", d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            case "year" -> String.valueOf(d.getYear());
            default -> d.format(DateTimeFormatter.ofPattern("yyyyMM"));   // month
        };
    }

    /** 惰性确保分片表存在（CREATE TABLE IF NOT EXISTS ... LIKE 基表·按后缀缓存避免重复 DDL）。 */
    private void ensureShard(String shardTable) {
        if (ensuredShards.contains(shardTable)) {
            return;
        }
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS `" + shardTable + "` LIKE `" + LOG_BASE_TABLE + "`");
            ensuredShards.add(shardTable);
        } catch (Exception e) {
            log.warn("分片表自动建表失败（回退当前表·不影响主链）{}: {}", shardTable, e.toString());
        }
    }

    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                LocalDateTime now = LocalDateTime.now();
                strictInsertFill(metaObject, FIELD_CREATE_DT, LocalDateTime.class, now);
                strictInsertFill(metaObject, FIELD_UPDATE_DT, LocalDateTime.class, now);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                strictUpdateFill(metaObject, FIELD_UPDATE_DT, LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
