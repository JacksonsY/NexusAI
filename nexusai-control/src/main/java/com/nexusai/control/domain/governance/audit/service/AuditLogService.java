package com.nexusai.control.domain.governance.audit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.nexusai.common.util.ClientIp;
import com.nexusai.control.domain.governance.audit.vo.AuditLogPageVo;
import com.nexusai.persistence.mapper.NaiAuditLogMapper;
import com.nexusai.persistence.mapper.NaiProjectMapper;
import com.nexusai.persistence.po.NaiAuditLog;
import com.nexusai.persistence.po.NaiProject;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 审计日志服务：异步落库（绝不阻塞请求线程）+ 分页查询 + 过期清理。
 * actor 解析：NexusAI-Admin-Token 头命中 active 项目令牌 → {@code project:{code}}，否则视为控制台 admin。
 *
 * @author nexusai
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final NaiAuditLogMapper auditLogMapper;
    private final NaiProjectMapper projectMapper;

    /**
     * 项目令牌 → projectCode 快照（写操作低频·5s 全量缓存足够）。
     * 🔴 用 refreshAfterWrite：{@link #resolveActor} 由 {@link com.nexusai.control.domain.governance.audit.filter.AdminAuditWebFilter}
     * 的 doFinally 在终态信号线程（常为 Netty 事件循环）调用，过期后异步重载（Caffeine 执行器·非调用线程）+
     * {@link #warmTokenSnapshot()} 预热，避免在事件循环上跑阻塞 JDBC selectList。
     */
    private final LoadingCache<String, Map<String, String>> tokenSnapshot = Caffeine.newBuilder()
            .refreshAfterWrite(Duration.ofSeconds(5))
            .maximumSize(1)
            .build(k -> loadTokenMap());

    /** bean 初始化线程预热令牌快照（非事件循环）——此后 resolveActor 恒命中本地值，重载异步进行。 */
    @PostConstruct
    void warmTokenSnapshot() {
        try {
            tokenSnapshot.get("all");
        } catch (Exception e) {
            log.warn("审计令牌快照预热失败: {}", e.toString());
        }
    }

    private Map<String, String> loadTokenMap() {
        try {
            return projectMapper.selectList(new LambdaQueryWrapper<NaiProject>().eq(NaiProject::getStatus, 1))
                    .stream()
                    .filter(p -> p.getToken() != null && !p.getToken().isBlank())
                    .collect(Collectors.toMap(NaiProject::getToken, NaiProject::getProjectCode, (a, b) -> a));
        } catch (Exception e) {
            log.warn("审计 actor 项目令牌快照加载失败: {}", e.toString());
            return Map.of();
        }
    }

    /** 由请求头令牌解析操作者标识。 */
    public String resolveActor(String adminToken) {
        if (adminToken != null && !adminToken.isBlank()) {
            String code = tokenSnapshot.get("all").get(adminToken);
            if (code != null) {
                return "project:" + code;
            }
        }
        return "admin";
    }

    /** 异步落库（boundedElastic·失败仅告警绝不影响主链路）。 */
    public void record(NaiAuditLog entry) {
        Mono.fromRunnable(() -> auditLogMapper.insert(entry))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(v -> {
                }, e -> log.warn("审计日志落库失败: {}", e.toString()));
    }

    /** 分页查询（actor 模糊 / 事件类型 / 最近 N 天）。 */
    public AuditLogPageVo page(String actor, String eventType, int days, int page, int pageSize) {
        LambdaQueryWrapper<NaiAuditLog> qw = new LambdaQueryWrapper<NaiAuditLog>()
                .ge(NaiAuditLog::getCreateDt, LocalDateTime.now().minusDays(Math.max(1, days)))
                .orderByDesc(NaiAuditLog::getId);
        if (actor != null && !actor.isBlank()) {
            qw.like(NaiAuditLog::getActor, actor);
        }
        if (eventType != null && !eventType.isBlank()) {
            qw.eq(NaiAuditLog::getEventType, eventType);
        }
        Page<NaiAuditLog> p = auditLogMapper.selectPage(
                new Page<>(Math.max(1, page), Math.min(200, Math.max(1, pageSize))), qw);
        return new AuditLogPageVo(p.getRecords(), p.getTotal());
    }

    /** 事件类型清单（前端筛选下拉用·当前库内出现过的类型）。 */
    public List<String> eventTypes() {
        return auditLogMapper.selectList(new LambdaQueryWrapper<NaiAuditLog>()
                        .select(NaiAuditLog::getEventType)
                        .groupBy(NaiAuditLog::getEventType))
                .stream().map(NaiAuditLog::getEventType).sorted().toList();
    }

    /** 删除 cutoff 之前的记录（CleanupJob 调用）。 */
    public int deleteBefore(LocalDateTime cutoff) {
        return auditLogMapper.delete(new LambdaQueryWrapper<NaiAuditLog>().lt(NaiAuditLog::getCreateDt, cutoff));
    }

    /**
     * 由路径+方法推导事件类型：/admin/channels POST → channel_created；
     * 特例端点单列（配置变更/导入/清空/模块开关）。
     */
    public static String deriveEventType(String path, String method) {
        String p = path.startsWith("/admin/") ? path.substring("/admin/".length()) : path;
        if (p.startsWith("system/configs")) {
            return "config_changed";
        }
        if (p.startsWith("system/config/import")) {
            return "config_imported";
        }
        if (p.startsWith("system/purge")) {
            return "data_purged";
        }
        if (p.startsWith("modules/")) {
            return "module_config_changed";
        }
        String resource = p.split("/")[0];
        // 资源名复数 → 单数（channels → channel）
        String singular = resource.endsWith("s") ? resource.substring(0, resource.length() - 1) : resource;
        String action = switch (method) {
            case "POST" -> "created";
            case "PUT", "PATCH" -> "updated";
            case "DELETE" -> "deleted";
            default -> "accessed";
        };
        return (singular + "_" + action).replace('-', '_');
    }

    /** 按可信代理配置解析真实客户端 IP，未配置可信代理时忽略 XFF。 */
    public static String clientIp(ServerWebExchange exchange) {
        String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        String remote = Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                .map(a -> a.getAddress().getHostAddress()).orElse("");
        return ClientIp.resolve(remote, xff);
    }
}
