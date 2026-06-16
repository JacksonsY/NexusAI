package com.nexusai.observe.log;

import com.nexusai.persistence.log.RequestBodyStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 请求正文捕获接线（数据面集成点）：把 relay 热路径已缓冲的请求体经 {@link RequestBodyStore} SPI
 * <b>fire-and-forget</b> 落正文分离库（{@code nexusai.log.body.store=rdb} 时生效；{@code hash} 档 isCollecting=false 直接跳过）。
 * <p>纪律：①仅在 store 启用采集时落库（默认 hash 档零开销）；②热路径外（boundedElastic）异步、吞异常——
 * 正文采集失败<b>绝不</b>影响主请求；③请求体 + 响应体双采（响应体由 relay 端聚合并钳 5MB 上限）。
 *
 * @author nexusai
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequestBodyCapture {

    private final RequestBodyStore bodyStore;

    /** 是否启用采集（relay 据此决定是否聚合响应体·hash 档恒 false 零开销）。 */
    public boolean isCollecting() {
        return bodyStore.isCollecting();
    }

    /** 采集一条请求/响应正文（requestId 关联日志主表）。store 未启用采集则空操作。 */
    public void capture(String requestId, byte[] requestBody, byte[] responseBody) {
        boolean empty = (requestBody == null || requestBody.length == 0)
                && (responseBody == null || responseBody.length == 0);
        if (empty || !bodyStore.isCollecting()) {
            return;
        }
        Mono.fromRunnable(() -> {
                    try {
                        bodyStore.save(requestId, requestBody, responseBody);
                    } catch (Exception ex) {
                        log.warn("请求正文采集落库失败（不影响主请求）requestId={}: {}", requestId, ex.toString());
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }
}
