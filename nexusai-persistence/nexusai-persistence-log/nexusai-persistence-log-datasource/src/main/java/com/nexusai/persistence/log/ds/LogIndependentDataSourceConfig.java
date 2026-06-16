package com.nexusai.persistence.log.ds;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 独立日志库装配（{@code nexusai.log.datasource.enabled=true} 时激活）：
 * 把 log-mysql/log-pg 的 mapper 从主 {@code SqlSessionFactory} 摘出，绑到本类产出的<b>独立</b> {@code logSqlSessionTemplate}。
 *
 * <p><b>关键纪律（沿用 ClickHouse driver 的范本·两层都不能暴露 bean）</b>：
 * ① 内部 {@code new HikariDataSource} <b>不暴露成 DataSource 类型 bean</b>——否则 {@code DataSourceAutoConfiguration} 退避不再建主源；
 * ② <b>不暴露第二个 {@code SqlSessionFactory} 类型 bean</b>——否则 MyBatis-Plus 的 {@code @ConditionalOnMissingBean(SqlSessionFactory)}
 * 退避、主业务工厂消失，<b>所有 mapper（含业务）都绑到日志工厂</b>（实测会让业务表查到日志库报 table doesn't exist）。
 * 故工厂仅作局部变量内联自建，只暴露 {@code SqlSessionTemplate}；业务 {@code @MapperScan} 用
 * {@code sqlSessionFactoryRef="sqlSessionFactory"}（MP 自动工厂 bean 名）显式绑定主工厂，与日志解耦。
 *
 * <p>独立工厂须自带 {@code mapUnderscoreToCamelCase}（主 yml 的 mybatis-plus 配置只作用于自动装配的主工厂）+
 * 按日志库方言（非业务方言）建分页拦截器。DataSource 由本类持有，销毁时关闭连接池。
 *
 * @author nexusai
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LogDataSourceProperties.class)
@ConditionalOnProperty(prefix = "nexusai.log.datasource", name = "enabled", havingValue = "true")
@MapperScan(
        basePackages = {"com.nexusai.persistence.log.mysql.mapper", "com.nexusai.persistence.log.pg.mapper"},
        sqlSessionTemplateRef = "logSqlSessionTemplate")
public class LogIndependentDataSourceConfig implements DisposableBean {

    private HikariDataSource logDataSource;

    @Bean
    public SqlSessionTemplate logSqlSessionTemplate(LogDataSourceProperties p) throws Exception {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(p.getUrl());
        c.setUsername(p.getUsername());
        c.setPassword(p.getPassword());
        if (p.getDriverClassName() != null && !p.getDriverClassName().isBlank()) {
            c.setDriverClassName(p.getDriverClassName());
        }
        c.setMaximumPoolSize(Math.max(1, p.getPoolSize()));
        c.setPoolName("nexusai-log-pool");
        this.logDataSource = new HikariDataSource(c);
        try {
            MybatisConfiguration conf = new MybatisConfiguration();
            conf.setMapUnderscoreToCamelCase(true);   // 主 yml 配置不作用于手建工厂·须显式开启

            // 工厂仅作局部变量·绝不暴露成 SqlSessionFactory bean（否则顶掉主业务工厂）
            MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
            bean.setDataSource(logDataSource);
            bean.setConfiguration(conf);
            bean.setPlugins(logPaginationInterceptor(p.getDialect()));
            SqlSessionFactory factory = bean.getObject();
            log.info("日志库独立连接已装配 url={} dialect={} pool={}", p.getUrl(), p.getDialect(), p.getPoolSize());
            return new SqlSessionTemplate(factory);
        } catch (Exception e) {
            // 工厂构建失败：立即释放已建连接池，避免泄漏（启动失败路径）
            logDataSource.close();
            logDataSource = null;
            throw e;
        }
    }

    /** 按日志库方言建分页拦截器（独立于业务 db.type）。 */
    private static MybatisPlusInterceptor logPaginationInterceptor(String dialect) {
        DbType dbType = switch (dialect == null ? "" : dialect.trim().toLowerCase()) {
            case "postgresql", "postgres", "pg" -> DbType.POSTGRE_SQL;
            default -> DbType.MYSQL;
        };
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor page = new PaginationInnerInterceptor(dbType);
        page.setMaxLimit(500L);
        interceptor.addInnerInterceptor(page);
        return interceptor;
    }

    @Override
    public void destroy() {
        if (logDataSource != null && !logDataSource.isClosed()) {
            logDataSource.close();
        }
    }
}
