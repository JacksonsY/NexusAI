package com.nexusai;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

/**
 * nexusai 启动入口（独立反应堆 · WebFlux AI 中转转发网关）。
 *
 * @author nexusai
 */
@SpringBootApplication(scanBasePackages = "com.nexusai")
@EnableConfigurationProperties
@EnableAsync
@EnableScheduling
// 业务 mapper 显式绑 MP 自动工厂（sqlSessionFactoryRef=sqlSessionFactory）——独立日志库启用时存在第二个工厂，
// 必须显式绑定否则业务表会查到日志库。日志 mapper（log.mysql/log.pg）由 log-datasource 模块的
// LogShared/LogIndependentDataSourceConfig 按 nexusai.log.datasource.enabled 扫进主源或独立源（日志库可与业务库物理分离）。
@MapperScan(basePackages = "com.nexusai.persistence.mapper", sqlSessionFactoryRef = "sqlSessionFactory")
public class NexusAiApplication {

    public static void main(String[] args) {
        // 强制 JVM 时区，避免容器 UTC 导致日志/计量时间错乱
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        SpringApplication.run(NexusAiApplication.class, args);
    }
}
