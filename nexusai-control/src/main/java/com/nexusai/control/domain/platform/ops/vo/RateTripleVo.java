package com.nexusai.control.domain.platform.ops.vo;

/**
 * 速率三元组（QPS/TPS 通用·current=最近 60s / peak=窗口最热桶 / avg=全窗均值）。
 * <p>组件名/类型与前端 {@code OpsRealtimeTraffic.qps|tps}（{current,peak,avg}）严格对齐，序列化 JSON 字节级一致。
 *
 * @author nexusai
 */
public record RateTripleVo(double current, double peak, double avg) {
}
