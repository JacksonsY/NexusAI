package com.nexusai.control.domain.platform.system.vo;

/**
 * 清空数据结果（不可逆操作回执）。
 * <p>组件名/类型与前端 {@code purgeSystemData} 期望的 {@code { deleted, target }} 严格对齐，
 * 序列化 JSON 字节级一致；{@code deleted} 为 -1 表示删除计数不可得（如分批未知总量）。
 *
 * @author nexusai
 */
public record PurgeResultVo(int deleted, String target) {
}
