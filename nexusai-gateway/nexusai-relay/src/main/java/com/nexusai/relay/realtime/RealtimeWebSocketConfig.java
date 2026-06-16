package com.nexusai.relay.realtime;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.Map;

/**
 * Realtime WebSocket 装配：把 {@link RealtimeWebSocketHandler} 映射到 {@code /v1/realtime}（优先级高于注解 mapping），
 * 并注册 {@link WebSocketHandlerAdapter} 使 WebFlux 能驱动 WS 握手升级。
 *
 * @author nexusai
 */
@Configuration
public class RealtimeWebSocketConfig {

    @Bean
    public HandlerMapping realtimeWebSocketMapping(RealtimeWebSocketHandler handler) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(Map.of("/v1/realtime", handler));
        mapping.setOrder(Ordered.HIGHEST_PRECEDENCE);   // 先于 RequestMappingHandlerMapping 命中 WS 路径
        return mapping;
    }

    @Bean
    public WebSocketHandlerAdapter webSocketHandlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}
