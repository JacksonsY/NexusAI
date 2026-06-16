package com.nexusai.persistence.log.clickhouse;

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
 * ClickHouse 日志装配（{@code nexusai.log.store=clickhouse}）：MyBatis-Plus + 独立 {@code SqlSessionFactory}
 * （{@link DbType#CLICK_HOUSE} 分页方言），把 CH mapper 绑到本类产出的独立 {@code clickHouseSqlSessionTemplate}。
 *
 * <p>沿用 log-datasource 的纪律：内部 {@code new HikariDataSource} + 工厂局部变量，<b>只暴露 SqlSessionTemplate</b>
 * （不暴露第二个 DataSource/SqlSessionFactory bean·否则顶掉主业务源/工厂）。Hikari {@code initializationFailTimeout=-1}
 * + {@code minimumIdle=0}：无 ClickHouse 服务端时应用照常启动（连接惰性）。clickhouse-jdbc(shaded all) 内置随网关。
 *
 * @author nexusai
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClickHouseLogProperties.class)
@ConditionalOnProperty(prefix = "nexusai.log", name = "store", havingValue = "clickhouse")
@MapperScan(basePackages = "com.nexusai.persistence.log.clickhouse.mapper",
        sqlSessionTemplateRef = "clickHouseSqlSessionTemplate")
public class ClickHouseLogConfig implements DisposableBean {

    private HikariDataSource dataSource;

    @Bean
    public SqlSessionTemplate clickHouseSqlSessionTemplate(ClickHouseLogProperties p) throws Exception {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(p.getUrl());
        c.setUsername(p.getUsername());
        c.setPassword(p.getPassword());
        if (p.getDriverClassName() != null && !p.getDriverClassName().isBlank()) {
            c.setDriverClassName(p.getDriverClassName());
        }
        c.setMaximumPoolSize(Math.max(1, p.getPoolSize()));
        c.setMinimumIdle(0);
        c.setInitializationFailTimeout(-1);   // 无 CH 服务端不阻断启动（连接惰性建立）
        c.setPoolName("nexusai-ch-pool");
        this.dataSource = new HikariDataSource(c);

        MybatisConfiguration conf = new MybatisConfiguration();
        conf.setMapUnderscoreToCamelCase(true);

        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor page = new PaginationInnerInterceptor(DbType.CLICK_HOUSE);
        page.setMaxLimit(500L);
        interceptor.addInnerInterceptor(page);

        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(dataSource);
        bean.setConfiguration(conf);
        bean.setPlugins(interceptor);
        SqlSessionFactory factory = bean.getObject();
        log.info("ClickHouse 日志库（MyBatis-Plus·DbType.CLICK_HOUSE）已装配 url={} pool={}", p.getUrl(), p.getPoolSize());
        return new SqlSessionTemplate(factory);
    }

    @Override
    public void destroy() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
