package com.nexusai.control.domain.routing.proxynode.controller;

import com.nexusai.common.model.R;
import com.nexusai.control.domain.routing.proxynode.service.ProxyNodeService;
import com.nexusai.control.domain.routing.proxynode.vo.ProxyNodeDeleteResultVo;
import com.nexusai.control.domain.routing.proxynode.vo.ProxyNodeDetailVo;
import com.nexusai.control.domain.routing.proxynode.vo.ProxyNodeEventVo;
import com.nexusai.control.domain.routing.proxynode.vo.ProxyNodeMetricsVo;
import com.nexusai.control.domain.routing.proxynode.vo.ProxyNodeVo;
import com.nexusai.control.domain.routing.proxynode.vo.ProxyTestResultVo;
import com.nexusai.persistence.po.NaiProxyNode;
import com.nexusai.persistence.po.NaiProxyNodeEvent;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * 出口代理节点 admin（/admin/proxy-nodes）：
 * 列表/详情/手动添加/编辑/删除/连通性测试/任意 URL 测试/24h 指标/连接事件。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/proxy-nodes")
@RequiredArgsConstructor
public class ProxyNodeAdminController {

    private final ProxyNodeService service;

    @GetMapping
    public Mono<R<List<ProxyNodeVo>>> list() {
        return blocking(() -> service.list().stream().map(ProxyNodeAdminController::toView).toList());
    }

    @GetMapping("/{id}")
    public Mono<R<ProxyNodeDetailVo>> get(@PathVariable long id) {
        return blocking(() -> {
            NaiProxyNode node = service.get(id);
            if (node == null) {
                return null;
            }
            // 编辑回显：admin 会话内回传明文密码（与渠道密钥编辑同纪律）
            return toDetail(node, service.plainPassword(node));
        });
    }

    @PostMapping("/manual")
    public Mono<R<ProxyNodeVo>> create(@RequestBody UpsertReq req) {
        return blocking(() -> toView(service.create(
                req.getName(), req.getProxyUrl(), req.getUsername(), req.getPassword(), req.getRegion())));
    }

    @PutMapping("/{id}")
    public Mono<R<ProxyNodeVo>> update(@PathVariable long id, @RequestBody UpsertReq req) {
        return blocking(() -> toView(service.update(
                id, req.getName(), req.getProxyUrl(), req.getUsername(), req.getPassword(), req.getRegion())));
    }

    @DeleteMapping("/{id}")
    public Mono<R<ProxyNodeDeleteResultVo>> delete(@PathVariable long id) {
        return blocking(() -> new ProxyNodeDeleteResultVo(service.delete(id)));
    }

    @PostMapping("/{id}/test")
    public Mono<R<ProxyTestResultVo>> test(@PathVariable long id) {
        return blocking(() -> service.testNode(id));
    }

    @PostMapping("/test-url")
    public Mono<R<ProxyTestResultVo>> testUrl(@RequestBody UpsertReq req) {
        return blocking(() -> service.probe(req.getProxyUrl(), req.getUsername(), req.getPassword()));
    }

    @GetMapping("/{id}/metrics")
    public Mono<R<ProxyNodeMetricsVo>> metrics(@PathVariable long id,
                                               @RequestParam(defaultValue = "24") int hours) {
        return blocking(() -> service.metrics(id, hours));
    }

    @GetMapping("/{id}/events")
    public Mono<R<List<ProxyNodeEventVo>>> events(@PathVariable long id,
                                                  @RequestParam(defaultValue = "50") int limit) {
        return blocking(() -> service.events(id, limit).stream()
                .map(ProxyNodeAdminController::eventView).toList());
    }

    private static ProxyNodeVo toView(NaiProxyNode node) {
        return new ProxyNodeVo(
                String.valueOf(node.getId()),
                node.getName(),
                node.getProxyUrl(),
                node.getUsername(),
                node.getPasswordEnc() != null && !node.getPasswordEnc().isBlank(),
                node.getRegion(),
                node.getStatus(),
                node.getTotalRequests(),
                node.getFailedRequests(),
                node.getAvgLatencyMs(),
                node.getLastProbeAt() == null ? null : node.getLastProbeAt().toString(),
                node.getLastOkAt() == null ? null : node.getLastOkAt().toString(),
                node.getCreateDt() == null ? null : node.getCreateDt().toString());
    }

    private static ProxyNodeDetailVo toDetail(NaiProxyNode node, String password) {
        return new ProxyNodeDetailVo(
                String.valueOf(node.getId()),
                node.getName(),
                node.getProxyUrl(),
                node.getUsername(),
                node.getPasswordEnc() != null && !node.getPasswordEnc().isBlank(),
                node.getRegion(),
                node.getStatus(),
                node.getTotalRequests(),
                node.getFailedRequests(),
                node.getAvgLatencyMs(),
                node.getLastProbeAt() == null ? null : node.getLastProbeAt().toString(),
                node.getLastOkAt() == null ? null : node.getLastOkAt().toString(),
                node.getCreateDt() == null ? null : node.getCreateDt().toString(),
                password);
    }

    private static ProxyNodeEventVo eventView(NaiProxyNodeEvent e) {
        return new ProxyNodeEventVo(
                String.valueOf(e.getId()),
                e.getEventType(),
                e.getDetail(),
                e.getCreateDt() == null ? null : e.getCreateDt().toString());
    }

    private static <T> Mono<R<T>> blocking(Callable<T> call) {
        return Mono.fromCallable(call).map(R::ok).subscribeOn(Schedulers.boundedElastic());
    }

    @Data
    public static class UpsertReq {
        private String name;
        private String proxyUrl;
        private String username;
        private String password;
        private String region;
    }
}
