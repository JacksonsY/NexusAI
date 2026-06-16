package com.nexusai.control.domain.project.usage.controller;

import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.common.model.PageQuery;
import com.nexusai.common.model.PageResult;
import com.nexusai.common.model.R;
import com.nexusai.control.api.security.CallerScope;
import com.nexusai.control.domain.project.usage.service.UsageQueryService;
import com.nexusai.control.domain.project.usage.vo.ActiveRequestVo;
import com.nexusai.control.domain.project.usage.vo.IntervalTimelineVo;
import com.nexusai.control.domain.project.usage.vo.UsageAggItemVo;
import com.nexusai.control.domain.project.usage.vo.UsageHeatmapVo;
import com.nexusai.persistence.log.RequestLogPage;
import com.nexusai.persistence.log.RequestLogQuery;
import com.nexusai.persistence.po.NaiUsageLog;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 用量/请求日志查询（/admin/usage·分页只读）。控制器只做参数绑定 + R 包装 + boundedElastic，
 * 域逻辑全在 {@link UsageQueryService}（域内薄分层）。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/usage")
@RequiredArgsConstructor
public class UsageQueryController {

    private final UsageQueryService usageQuery;

    /** 计量日志（nai_usage_log·计量事件 outbox 同源）。 */
    @GetMapping("/logs")
    public Mono<R<PageResult<NaiUsageLog>>> logs(@RequestParam(defaultValue = "1") long pageNum,
                                                 @RequestParam(defaultValue = "10") long pageSize,
                                                 @RequestParam(required = false) String projectCode,
                                                 @RequestParam(required = false) String model,
                                                 ServerWebExchange exchange) {
        PageQuery pq = PageQuery.of(pageNum, pageSize);
        String scoped = scopeProject(exchange, projectCode);
        return blocking(() -> R.ok(usageQuery.usageLogs(pq, scoped, model)));
    }

    /** 请求日志（nai_request_log·网关最大表·经 SPI 分页·存储无关·使用记录页多维筛选）。 */
    @GetMapping("/requests")
    public Mono<R<RequestLogPage>> requests(@RequestParam(defaultValue = "1") long pageNum,
                                            @RequestParam(defaultValue = "10") long pageSize,
                                            @RequestParam(required = false) String projectCode,
                                            @RequestParam(required = false) Long channelId,
                                            @RequestParam(required = false) String model,
                                            @RequestParam(required = false) String requestId,
                                            @RequestParam(required = false) String modality,
                                            @RequestParam(required = false) Boolean streamMode,
                                            @RequestParam(required = false) Boolean success,
                                            @RequestParam(required = false) Integer status,
                                            @RequestParam(required = false) String apiFormat,
                                            @RequestParam(required = false) String clientFamily,
                                            @RequestParam(required = false) String statusFilter,
                                            @RequestParam(required = false) String search,
                                            @RequestParam(required = false) Boolean hideUnknown,
                                            @RequestParam(required = false) Integer hours,
                                            @RequestParam(required = false) String beginTime,
                                            @RequestParam(required = false) String endTime,
                                            @RequestParam(required = false) String orderBy,
                                            @RequestParam(defaultValue = "true") boolean orderDesc,
                                            ServerWebExchange exchange) {
        RequestLogQuery q = buildQuery(pageNum, pageSize, scopeProject(exchange, projectCode), channelId, model, requestId,
                modality, streamMode, success, status, apiFormat, clientFamily, statusFilter,
                search, hideUnknown, hours, beginTime, endTime, orderBy, orderDesc);
        return blocking(() -> R.ok(usageQuery.requests(q)));
    }

    /** 维度聚合（使用记录页「用量分析」面板）：groupBy 经白名单映射物理列后交服务计算。 */
    @GetMapping("/requests/aggregation")
    public Mono<R<List<UsageAggItemVo>>> aggregation(
            @RequestParam String groupBy,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) Long channelId,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String modality,
            @RequestParam(required = false) Boolean streamMode,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) String apiFormat,
            @RequestParam(required = false) String clientFamily,
            @RequestParam(required = false) String statusFilter,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean hideUnknown,
            @RequestParam(required = false) Integer hours,
            @RequestParam(required = false) String beginTime,
            @RequestParam(required = false) String endTime,
            ServerWebExchange exchange) {
        String column = RequestLogQuery.AGG_COLUMNS.get(groupBy);
        if (column == null) {
            return Mono.just(R.<List<UsageAggItemVo>>fail("非法 groupBy: " + groupBy));
        }
        int capped = Math.min(Math.max(limit, 1), 100);
        RequestLogQuery q = buildQuery(1, 1, scopeProject(exchange, projectCode), channelId, model, null,
                modality, streamMode, success, null, apiFormat, clientFamily, statusFilter,
                search, hideUnknown, hours, beginTime, endTime, null, true);
        return blocking(() -> R.ok(usageQuery.aggregate(q, groupBy, column, capped)));
    }

    /** 活跃度热图（固定 365 天·零请求日补齐）：全局总览·无法行级过滤·仅平台作用域可见。 */
    @GetMapping("/requests/heatmap")
    public Mono<R<UsageHeatmapVo>> heatmap(ServerWebExchange exchange) {
        requirePlatform(exchange);
        return blocking(() -> R.ok(usageQuery.heatmap()));
    }

    /** 请求间隔时间线（近 N 小时·按项目分组差分·超限按占比采样）：跨项目总览·仅平台作用域可见。 */
    @GetMapping("/requests/interval-timeline")
    public Mono<R<IntervalTimelineVo>> intervalTimeline(@RequestParam(defaultValue = "24") int hours,
                                                        @RequestParam(defaultValue = "1500") int limit,
                                                        ServerWebExchange exchange) {
        requirePlatform(exchange);
        int cappedHours = Math.min(Math.max(hours, 1), 720);
        int cappedLimit = Math.min(Math.max(limit, 1), 5000);
        return blocking(() -> R.ok(usageQuery.intervalTimeline(cappedHours, cappedLimit)));
    }

    /** 进行中请求（进程内活跃注册表·1s 轮询数据源）：跨项目活跃总览·仅平台作用域可见。 */
    @GetMapping("/requests/active")
    public Mono<R<List<ActiveRequestVo>>> activeRequests(ServerWebExchange exchange) {
        requirePlatform(exchange);
        return blocking(() -> R.ok(usageQuery.activeRequests()));
    }

    /**
     * 请求/响应正文（RequestBodyStore SPI·仅 store=rdb 且采集期间的请求有正文）。
     * 🔴 原始正文含终端用户对话明文（读路径无脱敏）：仅平台运营/控制台可读，项目自助令牌一律 403——
     * 否则分销商可凭 requestId 读其终端用户对话内容（违背「默认不存 prompt 只存 hash」隐私基线）。
     */
    @GetMapping("/requests/{requestId}/body")
    public Mono<R<Map<String, Object>>> requestBody(@PathVariable String requestId, ServerWebExchange exchange) {
        requirePlatform(exchange);
        return blocking(() -> R.ok(usageQuery.requestBody(requestId, null)));
    }

    /** cURL 重放命令（生成对上游的重放·鉴权脱敏占位）：项目令牌仅可重放本项目请求。 */
    @GetMapping("/requests/{requestId}/curl")
    public Mono<R<Map<String, Object>>> requestCurl(@PathVariable String requestId, ServerWebExchange exchange) {
        String caller = callerProject(exchange);
        return blocking(() -> {
            Map<String, Object> data = usageQuery.replayCurl(requestId, caller);
            return data == null ? R.<Map<String, Object>>fail("请求不存在: " + requestId) : R.ok(data);
        });
    }

    /**
     * 调用方项目作用域（{@code CallerScopeWebFilter} 写入 {@link GatewayConstants#CALLER_PROJECT_ATTR}）：
     * 项目作用域令牌返回其 projectCode（只能查本项目）；平台作用域令牌/控制台会话返回 null（无限制）。
     */
    private static String callerProject(ServerWebExchange exchange) {
        Object v = exchange.getAttribute(GatewayConstants.CALLER_PROJECT_ATTR);
        return v == null ? null : v.toString();
    }

    /** 可行级过滤端点：委托 {@link CallerScope}（单项目 M2M 强制本项目 / 多项目运维须落授权集 / 平台令牌沿用入参）。 */
    private static String scopeProject(ServerWebExchange exchange, String requested) {
        return CallerScope.scopeProject(exchange, requested);
    }

    /** 不可行级过滤的跨项目总览端点：任何受限调用方（项目令牌/项目运维）一律 403（见 {@link CallerScope#requirePlatform}）。 */
    private static void requirePlatform(ServerWebExchange exchange) {
        CallerScope.requirePlatform(exchange);
    }

    /** 组装存储无关查询条件：beginTime/endTime（ISO·空格分隔均可）优先，未给时退回 hours 预设换算。 */
    private static RequestLogQuery buildQuery(long pageNum, long pageSize, String projectCode,
                                              Long channelId, String model, String requestId, String modality,
                                              Boolean streamMode, Boolean success, Integer status,
                                              String apiFormat, String clientFamily, String statusFilter,
                                              String search, Boolean hideUnknown, Integer hours,
                                              String beginTime, String endTime, String orderBy, boolean orderDesc) {
        LocalDateTime begin = parseTime(beginTime);
        LocalDateTime end = parseTime(endTime);
        if (begin == null && hours != null && hours > 0) {
            begin = LocalDateTime.now().minusHours(hours);
        }
        return new RequestLogQuery(projectCode, channelId, model, requestId, modality,
                streamMode, success, status, apiFormat, clientFamily, statusFilter,
                search, hideUnknown, begin, end, orderBy, orderDesc, pageNum, pageSize);
    }

    private static LocalDateTime parseTime(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return LocalDateTime.parse(s.trim().replace(' ', 'T'));
    }

    private static <T> Mono<R<T>> blocking(Supplier<R<T>> supplier) {
        return Mono.fromCallable(supplier::get).subscribeOn(Schedulers.boundedElastic());
    }
}
