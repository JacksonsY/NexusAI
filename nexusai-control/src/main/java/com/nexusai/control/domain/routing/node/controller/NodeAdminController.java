package com.nexusai.control.domain.routing.node.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexusai.common.model.R;
import com.nexusai.control.domain.routing.node.vo.ClusterSummaryVo;
import com.nexusai.control.domain.routing.node.vo.ClusterViewVo;
import com.nexusai.control.domain.routing.node.vo.NodeVo;
import com.nexusai.persistence.mapper.NaiNodeMapper;
import com.nexusai.persistence.po.NaiNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 集群节点 admin（/admin/nodes·多实例集群视图）：
 * 节点列表（心跳新鲜度三档判态 + 运行时快照）+ 集群汇总（在线数/leader/在途请求/熔断态模式）
 * + 手动剔除离线节点。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/nodes")
@RequiredArgsConstructor
public class NodeAdminController {

    /** 离线阈值（3× 心跳周期 15s）；1~3 倍区间为「迟滞」。 */
    private static final long OFFLINE_SECONDS = 45L;
    private static final long STALE_SECONDS = 16L;
    private static final String STATUS_OFFLINE = "offline";
    private static final String ROLE_LEADER = "leader";
    private static final String ROLE_WORKER = "worker";
    /** 熔断态真理源：始终经 RTopic 广播跨实例共享（H6·无开关·新实例 warm-start 继承在用熔断态） */
    private static final String CIRCUIT_STATE_STORE = "redis";

    private final NaiNodeMapper nodeMapper;

    /** 节点列表 + 集群汇总。 */
    @GetMapping
    public Mono<R<ClusterViewVo>> list() {
        return blocking(() -> {
            LocalDateTime now = LocalDateTime.now();
            List<NaiNode> nodes = nodeMapper.selectList(
                    new LambdaQueryWrapper<NaiNode>().orderByDesc(NaiNode::getUpdateDt));
            int online = 0;
            long totalActive = 0;
            String leader = null;
            List<NodeVo> items = new ArrayList<>(nodes.size());
            for (NaiNode n : nodes) {
                String status = status(n, now);
                if (!STATUS_OFFLINE.equals(status)) {
                    online++;
                    totalActive += n.getActiveRequests() == null ? 0 : n.getActiveRequests();
                    if (ROLE_LEADER.equals(n.getRole())) {
                        leader = n.getNodeId();
                    }
                }
                items.add(toView(n, status));
            }
            ClusterSummaryVo summary = new ClusterSummaryVo(
                    nodes.size(), online, leader, totalActive, CIRCUIT_STATE_STORE);
            return new ClusterViewVo(items, summary);
        });
    }

    /** 手动剔除节点（离线节点清理·在线节点下次心跳会重新注册）。 */
    @DeleteMapping("/{nodeId}")
    public Mono<R<Void>> remove(@PathVariable String nodeId) {
        return blocking(() -> {
            nodeMapper.delete(new LambdaQueryWrapper<NaiNode>().eq(NaiNode::getNodeId, nodeId));
            return null;
        });
    }

    private static String status(NaiNode n, LocalDateTime now) {
        if (n.getUpdateDt() == null) {
            return STATUS_OFFLINE;
        }
        long age = Duration.between(n.getUpdateDt(), now).getSeconds();
        if (age <= STALE_SECONDS) {
            return "online";
        }
        return age <= OFFLINE_SECONDS ? "stale" : STATUS_OFFLINE;
    }

    private static NodeVo toView(NaiNode n, String status) {
        String role = STATUS_OFFLINE.equals(status) ? ROLE_WORKER : (n.getRole() == null ? ROLE_WORKER : n.getRole());
        return new NodeVo(
                n.getNodeId(),
                n.getHost(),
                n.getPort(),
                n.getVersion(),
                n.getStartedAt(),
                n.getUpdateDt() == null ? null : n.getUpdateDt().toString(),
                status,
                role,
                n.getActiveRequests(),
                n.getCpuPercent(),
                n.getHeapUsedMb(),
                n.getHeapMaxMb(),
                n.getLabels(),
                n.getCreateDt() == null ? null : n.getCreateDt().toString());
    }

    private static <T> Mono<R<T>> blocking(Callable<T> call) {
        return Mono.fromCallable(call).map(R::ok).defaultIfEmpty(R.ok()).subscribeOn(Schedulers.boundedElastic());
    }
}
