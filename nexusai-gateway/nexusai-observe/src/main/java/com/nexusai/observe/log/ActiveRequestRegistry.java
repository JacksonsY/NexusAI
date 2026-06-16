package com.nexusai.observe.log;

import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进行中请求注册表（进程内·活跃请求视图）：nexusai 的请求日志在<b>流末</b>才落库，
 * 进行中（pending/streaming）请求没有 DB 行——本注册表在转发管线生命周期内登记/更新/移除，
 * 供使用记录页活跃行轮询（1s）与「活跃」状态筛选。
 * <p>纯内存 ConcurrentHashMap（单实例 MVP 与熔断/槽位同款定位）；管线 doFinally 兜底移除，
 * 另设 5 分钟兜底过期（极端泄漏不至于永久残留幽灵行）。
 *
 * @author nexusai
 */
@Component
public class ActiveRequestRegistry {

    /** 快照返回上限（50）。 */
    private static final int SNAPSHOT_MAX = 50;
    /** 兜底过期（ms·防异常路径泄漏幽灵行）。 */
    private static final long STALE_MS = 5 * 60 * 1000L;

    private final ConcurrentHashMap<String, ActiveRequest> active = new ConcurrentHashMap<>();

    /**
     * 进行中请求（注册表私有可变载体·快照时拷贝）。
     * <p>用 {@link Getter}/{@link Setter} 而非 {@code @Data}：含 volatile 可变身份敏感态（phase/ttftMs/httpStatus），
     * 作为 ConcurrentHashMap 的可变值条目按 requestId 标识，不应生成跨全字段（含 volatile）的 equals/hashCode/toString。
     */
    @Getter
    @Setter
    public static class ActiveRequest {
        private String requestId;
        private String projectCode;
        private String model;
        private String targetModel;
        private Long channelId;
        private Long keyId;
        private String modality;
        private Boolean streamMode;
        private String apiFormat;
        private String endpointApiFormat;
        private String clientIp;
        private String userAgent;
        private String clientFamily;
        private long startedAtMillis;
        /** pending=未收到上游首字节 / streaming=响应回写中 */
        private volatile String phase = "pending";
        private volatile Long ttftMs;
        private volatile Integer httpStatus;
    }

    /** 闸门链通过后登记（pending）。 */
    public void register(ActiveRequest req) {
        if (req != null && req.getRequestId() != null) {
            active.put(req.getRequestId(), req);
        }
    }

    /** 路由选号后补渠道/Key/目标模型/上游格式（故障转移换渠道时覆盖为最新一跳）。 */
    public void updateRoute(String requestId, Long channelId, Long keyId, String targetModel, String endpointApiFormat) {
        ActiveRequest r = active.get(requestId);
        if (r != null) {
            r.setChannelId(channelId);
            r.setKeyId(keyId);
            r.setTargetModel(targetModel);
            r.setEndpointApiFormat(endpointApiFormat);
        }
    }

    /** 上游响应开始回写（首字节已到）→ streaming + TTFT + 状态码。 */
    public void markStreaming(String requestId, Long ttftMs, Integer httpStatus) {
        ActiveRequest r = active.get(requestId);
        if (r != null) {
            r.setPhase("streaming");
            r.setTtftMs(ttftMs);
            r.setHttpStatus(httpStatus);
        }
    }

    /** 终态移除（管线 doFinally 兜底·完成后日志行经 Redis Stream 落库接棒）。 */
    public void remove(String requestId) {
        if (requestId != null) {
            active.remove(requestId);
        }
    }

    /** 当前进行中快照（按开始时间倒序·上限 {@value #SNAPSHOT_MAX}·顺手清理兜底过期）。 */
    public List<ActiveRequest> snapshot() {
        long now = System.currentTimeMillis();
        active.values().removeIf(r -> now - r.getStartedAtMillis() > STALE_MS);
        return active.values().stream()
                .sorted(Comparator.comparingLong(ActiveRequest::getStartedAtMillis).reversed())
                .limit(SNAPSHOT_MAX)
                .toList();
    }

    public int size() {
        return active.size();
    }
}
