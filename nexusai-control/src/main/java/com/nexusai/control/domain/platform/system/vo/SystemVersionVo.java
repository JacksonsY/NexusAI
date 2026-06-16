package com.nexusai.control.domain.platform.system.vo;

/**
 * 系统版本信息（系统信息 section）。
 * <p>组件名/类型与前端 {@code NexusAI.SystemVersion} 严格对齐，序列化 JSON 字节级一致；
 * 键序与原 LinkedHashMap 一致。
 *
 * @author nexusai
 */
public record SystemVersionVo(String version, String springBoot, String java,
                              long uptimeMs, long startedAt) {
}
