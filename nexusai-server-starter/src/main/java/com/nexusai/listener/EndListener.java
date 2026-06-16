package com.nexusai.listener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

/**
 * 关闭监听器（结构对齐参考网关的 EndListener·实现适配 nexusai）。
 * <p>容器关闭时打印一行下线日志；各守护组件的资源释放由其 @PreDestroy 完成，这里不做集中编排。
 */
@Component
@Slf4j
public class EndListener implements ApplicationListener<ContextClosedEvent> {

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        log.info("NexusAI 网关开始关闭…");
    }
}
