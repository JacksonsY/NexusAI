package com.nexusai.control.domain.monitor.observe.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexusai.persistence.log.RequestLogStore;
import com.nexusai.persistence.log.stat.HealthBucket;
import com.nexusai.persistence.log.stat.HealthStat;
import com.nexusai.persistence.log.stat.PerfBucket;
import com.nexusai.persistence.mapper.NaiChannelKeyMapper;
import com.nexusai.persistence.mapper.NaiChannelMapper;
import com.nexusai.persistence.po.NaiChannel;
import com.nexusai.persistence.po.NaiChannelKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * 健康监控服务（健康监控页·三 Tab 口径）：
 * 协议端点 / 模型 / 渠道三维度的「状态页式」健康时间线 + 可用率聚合。
 * <p>判色与排序口径：
 * <ul>
 *   <li><b>时间线判色</b>：每段成功率 ≥0.95 healthy / ≥0.7 warning / &lt;0.7 unhealthy / 无请求 unknown；</li>
 *   <li><b>健康标签</b>（卡片 Badge，前端判定）：≥0.95 正常 / ≥0.8 波动 / &lt;0.8 异常 / 无请求；</li>
 *   <li><b>排序</b>：渠道 Tab 故障排前（rank：&lt;0.8→0、&lt;0.95→1、正常→2、无请求→3，同 rank 按名）；</li>
 *   <li><b>协议合并</b>：日志按 channel_id 分桶聚合 → 内存映射 protocol 合并（端点→协议家族，
 *       不给日志最大表加冗余协议列）。</li>
 * </ul>
 * 与 {@code /admin/runtime}（实时快照：熔断/打分）互补——本服务回答「最近哪条链路坏过」（历史回放）。
 *
 * @author nexusai
 */
@Service
@RequiredArgsConstructor
public class HealthMonitorService {

    private final RequestLogStore logStore;
    private final NaiChannelMapper channelMapper;
    private final NaiChannelKeyMapper channelKeyMapper;

    /** 协议端点时间线段数（端点 100 格）。 */
    private static final int PROTOCOL_SEGMENTS = 100;
    /** 模型/渠道时间线段数（历史 60 点）。 */
    private static final int MODEL_SEGMENTS = 60;
    /** 性能详情曲线段数（详情钻取·24h 默认窗口下每段 30 分钟）。 */
    private static final int SERIES_SEGMENTS = 48;
    /** 时间线判色阈值：成功率 ≥ 该值为 healthy。 */
    private static final double HEALTHY_RATE = 0.95;
    /** 时间线判色阈值：成功率 ≥ 该值为 warning（否则 unhealthy）。 */
    private static final double WARNING_RATE = 0.70;
    /** 故障排序阈值：成功率 &lt; 该值排最前（rank 0）。 */
    private static final double FAULT_RATE = 0.80;

    /** 协议端点健康监控：按渠道协议族聚合（OpenAI/Anthropic/Gemini…）。 */
    public Map<String, Object> protocols(int lookbackHours) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusHours(lookbackHours);

        List<NaiChannel> channels = channelMapper.selectList(null);
        Map<Long, String> channelProtocol = new HashMap<>();
        // 骨架 = 启用渠道的协议集合（无流量也展示全灰时间线·启用端点骨架）
        Map<String, Set<Long>> activeChannelsByProtocol = new TreeMap<>();
        for (NaiChannel ch : channels) {
            String protocol = normalizeProtocol(ch.getProtocol());
            channelProtocol.put(ch.getId(), protocol);
            if (ch.getStatus() != null && ch.getStatus() == 1) {
                activeChannelsByProtocol.computeIfAbsent(protocol, k -> new HashSet<>()).add(ch.getId());
            }
        }
        // 凭证数（配置面计数·启用 Key 按渠道协议归并）
        Map<String, Long> keyCountByProtocol = new HashMap<>();
        for (NaiChannelKey key : channelKeyMapper.selectList(
                Wrappers.lambdaQuery(NaiChannelKey.class).eq(NaiChannelKey::getStatus, 1))) {
            String protocol = channelProtocol.get(key.getChannelId());
            if (protocol != null) {
                keyCountByProtocol.merge(protocol, 1L, Long::sum);
            }
        }

        // 流量面：channel_id 维度聚合 → 协议合并（含已停用渠道的历史流量，不丢数据）
        Map<String, long[]> totalsByProtocol = new HashMap<>();
        Map<String, LocalDateTime> lastEventByProtocol = new HashMap<>();
        for (HealthStat stat : logStore.healthByDimension("channel_id", null, since)) {
            String protocol = channelProtocol.getOrDefault(parseLong(stat.key()), "unknown");
            long[] acc = totalsByProtocol.computeIfAbsent(protocol, k -> new long[2]);
            acc[0] += stat.total();
            acc[1] += stat.success();
            if (stat.lastEventAt() != null) {
                lastEventByProtocol.merge(protocol, stat.lastEventAt(),
                        (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        Map<String, long[][]> bucketsByProtocol = mergeBuckets(
                logStore.healthTimeline("channel_id", null, since, now, PROTOCOL_SEGMENTS),
                b -> channelProtocol.getOrDefault(parseLong(b.key()), "unknown"), PROTOCOL_SEGMENTS);

        // 协议全集 = 启用渠道协议 ∪ 有流量协议
        Set<String> protocols = new TreeSet<>(activeChannelsByProtocol.keySet());
        protocols.addAll(totalsByProtocol.keySet());

        List<Map<String, Object>> formats = new ArrayList<>();
        for (String protocol : protocols) {
            long[] acc = totalsByProtocol.getOrDefault(protocol, new long[2]);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("protocol", protocol);
            putCounts(row, acc[0], acc[1]);
            row.put("channelCount", activeChannelsByProtocol.getOrDefault(protocol, Set.of()).size());
            row.put("keyCount", keyCountByProtocol.getOrDefault(protocol, 0L));
            row.put("lastEventAt", lastEventByProtocol.get(protocol));
            putTimeline(row, bucketsByProtocol.get(protocol), PROTOCOL_SEGMENTS, since, now);
            formats.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("generatedAt", now);
        data.put("formats", formats);
        return data;
    }

    /** 模型健康监控：按模型聚合（可用率/时延/TTFT/渠道数），请求量降序取 Top modelLimit。 */
    public Map<String, Object> models(int lookbackHours, int modelLimit) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusHours(lookbackHours);

        List<HealthStat> stats = new ArrayList<>(logStore.healthByDimension("model", null, since));
        stats.sort(Comparator.comparingLong(HealthStat::total).reversed());
        if (stats.size() > modelLimit) {
            stats = stats.subList(0, modelLimit);
        }
        Map<String, long[][]> buckets = mergeBuckets(
                logStore.healthTimeline("model", null, since, now, MODEL_SEGMENTS),
                HealthBucket::key, MODEL_SEGMENTS);

        List<Map<String, Object>> models = new ArrayList<>();
        for (HealthStat stat : stats) {
            models.add(modelRow(stat, buckets.get(stat.key()), since, now));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("generatedAt", now);
        data.put("models", models);
        return data;
    }

    /** 渠道健康监控：按渠道聚合 + 下挂该渠道模型明细（故障排前）。 */
    public Map<String, Object> channels(int lookbackHours, int perChannelModelLimit) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusHours(lookbackHours);

        Map<Long, NaiChannel> channelById = new HashMap<>();
        for (NaiChannel ch : channelMapper.selectList(null)) {
            channelById.put(ch.getId(), ch);
        }

        Map<String, HealthStat> chStats = new HashMap<>();
        for (HealthStat stat : logStore.healthByDimension("channel_id", null, since)) {
            chStats.put(stat.key(), stat);
        }
        Map<String, long[][]> chBuckets = mergeBuckets(
                logStore.healthTimeline("channel_id", null, since, now, MODEL_SEGMENTS),
                HealthBucket::key, MODEL_SEGMENTS);
        // 渠道 × 模型 二维聚合（一次查询防 N+1·替代按提供商逐个查）
        Map<String, List<HealthStat>> modelStatsByChannel = new HashMap<>();
        for (HealthStat stat : logStore.healthByDimension("channel_id", "model", since)) {
            modelStatsByChannel.computeIfAbsent(stat.key(), k -> new ArrayList<>()).add(stat);
        }
        Map<String, Map<String, long[][]>> modelBucketsByChannel = new HashMap<>();
        for (HealthBucket bucket : logStore.healthTimeline("channel_id", "model", since, now, MODEL_SEGMENTS)) {
            long[][] grid = modelBucketsByChannel
                    .computeIfAbsent(bucket.key(), k -> new HashMap<>())
                    .computeIfAbsent(bucket.subKey(), k -> new long[MODEL_SEGMENTS][2]);
            accumulate(grid, bucket);
        }

        // 骨架 = 启用渠道 ∪ 近窗有流量渠道（停用但有历史流量的也展示，标注状态）
        Set<Long> channelIds = new HashSet<>();
        for (NaiChannel ch : channelById.values()) {
            if (ch.getStatus() != null && ch.getStatus() == 1) {
                channelIds.add(ch.getId());
            }
        }
        for (String key : chStats.keySet()) {
            Long id = parseLong(key);
            if (id != null) {
                channelIds.add(id);
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Long channelId : channelIds) {
            NaiChannel ch = channelById.get(channelId);
            String key = String.valueOf(channelId);
            HealthStat stat = chStats.get(key);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("channelId", channelId);
            row.put("channelName", ch != null ? ch.getName() : "渠道 " + channelId);
            row.put("protocol", ch != null ? normalizeProtocol(ch.getProtocol()) : "unknown");
            row.put("status", ch != null ? ch.getStatus() : null);
            putCounts(row, stat == null ? 0 : stat.total(), stat == null ? 0 : stat.success());
            row.put("avgLatencyMs", stat == null ? null : stat.avgDurationMs());
            row.put("avgFirstByteMs", stat == null ? null : stat.avgTtftMs());
            row.put("lastEventAt", stat == null ? null : stat.lastEventAt());
            putTimeline(row, chBuckets.get(key), MODEL_SEGMENTS, since, now);

            List<HealthStat> modelStats = new ArrayList<>(modelStatsByChannel.getOrDefault(key, List.of()));
            row.put("modelCount", modelStats.size());
            modelStats.sort(Comparator.comparingLong(HealthStat::total).reversed());
            if (modelStats.size() > perChannelModelLimit) {
                modelStats = modelStats.subList(0, perChannelModelLimit);
            }
            Map<String, long[][]> modelBuckets = modelBucketsByChannel.getOrDefault(key, Map.of());
            List<Map<String, Object>> models = new ArrayList<>();
            for (HealthStat ms : modelStats) {
                models.add(modelRow(ms, modelBuckets.get(ms.subKey()), since, now));
            }
            row.put("models", models);
            rows.add(row);
        }
        // 故障排前（按健康排序 rank），同 rank 按渠道名
        rows.sort(Comparator.comparingInt(HealthMonitorService::sortRank)
                .thenComparing(r -> String.valueOf(r.get("channelName"))));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("generatedAt", now);
        data.put("channels", rows);
        return data;
    }

    /**
     * 性能详情钻取（逐桶曲线 + 汇总 + 对端维度分解表）：
     * dim=model → 该模型逐桶 TPS/延迟/TTFT/成功率曲线 + 分渠道性能表（按渠道分解·比按分组更利于运维定位）；
     * dim=channel → 该渠道曲线 + 分模型性能表。读时聚合直查日志（不建预聚合表——未上线无量级压力，
     * 单一真理源优先；量大切 ClickHouse store 即可）。
     */
    public Map<String, Object> series(String dim, String key, int lookbackHours) {
        String column = switch (dim) {
            case "model" -> "model";
            case "channel" -> "channel_id";
            default -> throw new IllegalArgumentException("dim 仅支持 model/channel");
        };
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusHours(lookbackHours);
        long rangeSecs = Math.max(Duration.between(since, now).getSeconds(), 1);

        // 逐桶曲线（固定 48 段·空段 requestCount=0 其余 null → 前端断线不连）
        PerfBucket[] bySeg = new PerfBucket[SERIES_SEGMENTS];
        for (PerfBucket bucket : logStore.perfTimeline(column, key, since, now, SERIES_SEGMENTS)) {
            int idx = Math.min(Math.max(bucket.segmentIdx(), 0), SERIES_SEGMENTS - 1);
            bySeg[idx] = bucket;
        }
        List<Map<String, Object>> series = new ArrayList<>(SERIES_SEGMENTS);
        long total = 0;
        long success = 0;
        long latSum = 0;
        long ttftSum = 0;
        long ttftCnt = 0;
        long tokens = 0;
        long genMs = 0;
        for (int i = 0; i < SERIES_SEGMENTS; i++) {
            PerfBucket b = bySeg[i];
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("ts", since.plusSeconds(rangeSecs * i / SERIES_SEGMENTS));
            if (b == null || b.total() == 0) {
                point.put("requestCount", 0);
            } else {
                total += b.total();
                success += b.success();
                latSum += b.latencySum();
                ttftSum += b.ttftSum();
                ttftCnt += b.ttftCount();
                tokens += b.tokensCount();
                genMs += b.genMsSum();
                point.put("requestCount", b.total());
                point.put("successRate", (double) b.success() / b.total());
                point.put("avgLatencyMs", b.latencySum() / b.total());
                point.put("avgTtftMs", b.ttftCount() > 0 ? b.ttftSum() / b.ttftCount() : null);
                point.put("avgTps", b.genMsSum() > 0 ? b.tokensCount() * 1000.0 / b.genMsSum() : 0D);
            }
            series.add(point);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        putCounts(summary, total, success);
        summary.put("avgLatencyMs", total > 0 ? latSum / total : null);
        summary.put("avgTtftMs", ttftCnt > 0 ? ttftSum / ttftCnt : null);
        summary.put("avgTps", genMs > 0 ? tokens * 1000.0 / genMs : 0D);

        // 对端维度分解表（dim=model→分渠道·dim=channel→分模型）：复用渠道×模型二维查询
        Map<Long, NaiChannel> channelById = new HashMap<>();
        for (NaiChannel ch : channelMapper.selectList(null)) {
            channelById.put(ch.getId(), ch);
        }
        boolean byModel = "model".equals(dim);
        List<HealthStat> twoDim = logStore.healthByDimension("channel_id", "model", since).stream()
                .filter(s -> byModel ? key.equals(s.subKey()) : key.equals(s.key()))
                .sorted(Comparator.comparingLong(HealthStat::total).reversed())
                .toList();
        Map<String, long[][]> grids = new HashMap<>();
        for (HealthBucket bucket : logStore.healthTimeline("channel_id", "model", since, now, MODEL_SEGMENTS)) {
            if (byModel ? !key.equals(bucket.subKey()) : !key.equals(bucket.key())) {
                continue;
            }
            long[][] grid = grids.computeIfAbsent(byModel ? bucket.key() : bucket.subKey(),
                    k -> new long[MODEL_SEGMENTS][2]);
            accumulate(grid, bucket);
        }
        List<Map<String, Object>> breakdown = new ArrayList<>();
        for (HealthStat stat : twoDim) {
            Map<String, Object> row = new LinkedHashMap<>();
            if (byModel) {
                NaiChannel ch = channelById.get(parseLong(stat.key()));
                row.put("key", stat.key());
                row.put("name", ch != null ? ch.getName() : "渠道 " + stat.key());
                row.put("protocol", ch != null ? normalizeProtocol(ch.getProtocol()) : "unknown");
            } else {
                row.put("key", stat.subKey());
                row.put("name", stat.subKey());
            }
            putCounts(row, stat.total(), stat.success());
            row.put("avgTps", stat.throughput());
            row.put("avgTtftMs", stat.avgTtftMs());
            row.put("avgLatencyMs", stat.avgDurationMs());
            putTimeline(row, grids.get(byModel ? stat.key() : stat.subKey()), MODEL_SEGMENTS, since, now);
            breakdown.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("generatedAt", now);
        data.put("dim", dim);
        data.put("key", key);
        data.put("summary", summary);
        data.put("series", series);
        data.put("breakdown", breakdown);
        return data;
    }

    // ===== 合成工具 =====

    /** 模型卡行（模型 Tab 与渠道下挂明细共形）。subKey 非空时（渠道×模型查询）模型名在 subKey。 */
    private Map<String, Object> modelRow(HealthStat stat, long[][] grid, LocalDateTime since, LocalDateTime now) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("model", stat.subKey() != null ? stat.subKey() : stat.key());
        putCounts(row, stat.total(), stat.success());
        row.put("avgLatencyMs", stat.avgDurationMs());
        row.put("avgFirstByteMs", stat.avgTtftMs());
        row.put("channelCount", stat.channelCount());
        row.put("lastEventAt", stat.lastEventAt());
        putTimeline(row, grid, MODEL_SEGMENTS, since, now);
        return row;
    }

    /** 计数四件套：总量/成功/失败/成功率（无请求成功率 1.0）。 */
    private static void putCounts(Map<String, Object> row, long total, long success) {
        row.put("totalAttempts", total);
        row.put("successCount", success);
        row.put("failedCount", total - success);
        row.put("successRate", total > 0 ? (double) success / total : 1.0);
    }

    /** 时间线 + 起止时间：每段判色（≥0.95 healthy / ≥0.7 warning / 其余 unhealthy / 无请求 unknown）。 */
    private static void putTimeline(Map<String, Object> row, long[][] grid, int segments,
                                    LocalDateTime since, LocalDateTime now) {
        List<String> timeline = new ArrayList<>(segments);
        for (int i = 0; i < segments; i++) {
            long total = grid == null ? 0 : grid[i][0];
            long success = grid == null ? 0 : grid[i][1];
            if (total == 0) {
                timeline.add("unknown");
            } else {
                double rate = (double) success / total;
                timeline.add(rate >= HEALTHY_RATE ? "healthy" : rate >= WARNING_RATE ? "warning" : "unhealthy");
            }
        }
        row.put("timeline", timeline);
        row.put("timeRangeStart", since);
        row.put("timeRangeEnd", now);
    }

    /** 分桶行 → keyOf 归并后的 [segments][total, success] 网格（协议合并/单维直通共用）。 */
    private static Map<String, long[][]> mergeBuckets(List<HealthBucket> buckets,
                                                      Function<HealthBucket, String> keyOf,
                                                      int segments) {
        Map<String, long[][]> out = new HashMap<>();
        for (HealthBucket bucket : buckets) {
            long[][] grid = out.computeIfAbsent(keyOf.apply(bucket), k -> new long[segments][2]);
            accumulate(grid, bucket);
        }
        return out;
    }

    private static void accumulate(long[][] grid, HealthBucket bucket) {
        int idx = Math.min(Math.max(bucket.segmentIdx(), 0), grid.length - 1);
        grid[idx][0] += bucket.total();
        grid[idx][1] += bucket.success();
    }

    /** 故障排前 rank：无请求 3 / 率&lt;0.8→0 / &lt;0.95→1 / 正常 2。 */
    private static int sortRank(Map<String, Object> row) {
        long total = ((Number) row.get("totalAttempts")).longValue();
        if (total == 0) {
            return 3;
        }
        double rate = ((Number) row.get("successRate")).doubleValue();
        if (rate < FAULT_RATE) {
            return 0;
        }
        return rate < HEALTHY_RATE ? 1 : 2;
    }

    private static String normalizeProtocol(String protocol) {
        return protocol == null || protocol.isBlank() ? "unknown" : protocol.trim().toLowerCase();
    }

    private static Long parseLong(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
