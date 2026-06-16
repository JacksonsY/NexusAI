package com.nexusai.control.domain.platform.ops;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 后台守护任务心跳注册表（进程内静态表）：
 * 各守护线程每轮成功跑完调 {@link #beat(String)}，运维监控页读快照判健康
 * （超过 5 分钟无心跳视为停摆）。
 *
 * @author nexusai
 */
public final class JobHeartbeats {

    /** 心跳健康判定窗口（秒） */
    private static final long STALE_SECONDS = 300;

    private static final Map<String, LocalDateTime> BEATS = new ConcurrentHashMap<>();

    private JobHeartbeats() {
    }

    /** 守护任务每轮跑完上报一次心跳。 */
    public static void beat(String jobName) {
        BEATS.put(jobName, LocalDateTime.now());
    }

    /** 心跳快照（name/lastRunDt/ok）。 */
    public static List<Map<String, Object>> snapshot() {
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> out = new ArrayList<>();
        BEATS.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", e.getKey());
            row.put("lastRunDt", e.getValue());
            row.put("ok", Duration.between(e.getValue(), now).getSeconds() <= STALE_SECONDS);
            out.add(row);
        });
        return out;
    }
}
