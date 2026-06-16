package com.nexusai.persistence.log.ds;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * 共享主源装配（缺省·{@code nexusai.log.datasource.enabled=false} 或不配时激活）：
 * 日志 mapper 扫进主业务 {@code SqlSessionFactory}（日志与业务共用一个库·小规模部署最省）。
 * <p>与 {@link LogIndependentDataSourceConfig} 互斥（一个扫进主源、一个扫进独立源），保证日志 mapper 只被扫一次（不重名冲突）。
 * 日志 mapper 包从主程序 {@code @MapperScan} 摘出后改由本类（或独立类）承接，避免漏扫。
 *
 * @author nexusai
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "nexusai.log.datasource", name = "enabled", havingValue = "false", matchIfMissing = true)
// 显式绑主工厂：store=clickhouse 时存在独立 CH SqlSessionTemplate，缺省按类型自动注入会歧义，故指明 sqlSessionFactory
@MapperScan(basePackages = {"com.nexusai.persistence.log.mysql.mapper", "com.nexusai.persistence.log.pg.mapper"},
        sqlSessionFactoryRef = "sqlSessionFactory")
public class LogSharedDataSourceConfig {
}
