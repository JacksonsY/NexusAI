package com.nexusai.control.domain.governance.ipsec.vo;

/**
 * IP 黑名单条目（含剩余 TTL 秒·-1=永久）。
 * <p>组件名/类型与前端 {@code NexusAI.IpBlacklistEntry} 严格对齐，序列化 JSON 字节级一致；
 * 键序与原 LinkedHashMap 一致。
 *
 * @author nexusai
 */
public record IpBlacklistEntryVo(String ip, String reason, long ttlSeconds) {
}
