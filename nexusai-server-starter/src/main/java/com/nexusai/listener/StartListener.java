package com.nexusai.listener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * 系统启动监听器（结构对齐参考网关的 StartListener·实现适配 nexusai）。
 * <p>容器刷新完成后打印一行启动成功日志；幂等（多次 refresh 只记一次）。
 * 守护组件的启停由各自 @PostConstruct/@PreDestroy 管理，这里不做集中编排。
 */
@Component
@Slf4j
public class StartListener implements ApplicationListener<ContextRefreshedEvent> {

    private final BuildProperties buildProperties;
    private volatile boolean started = false;

    public StartListener(@Nullable BuildProperties buildProperties) {
        this.buildProperties = buildProperties;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (started) {
            return;
        }
        started = true;
        String version = buildProperties != null ? buildProperties.getVersion() : "dev";
        log.info("NexusAI 网关启动成功 v{}", version);
    }
}
