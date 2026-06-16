package com.nexusai.control.domain.platform.ops.vo;

/**
 * 实时流量窗口（窗口可切 60/300/1800/3600 秒·current=最近 60s 速率·peak/avg=窗口细分桶最大/全窗均值）。
 * <p>组件名/类型与前端 {@code NexusAI.OpsRealtimeTraffic} 严格对齐，序列化 JSON 字节级一致。
 *
 * @author nexusai
 */
public record OpsRealtimeTrafficVo(int windowSeconds, RateTripleVo qps, RateTripleVo tps) {
}
