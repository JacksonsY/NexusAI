package com.nexusai.control.domain.platform.system.vo;

/**
 * 单实体配置导入计数（created/updated/skipped）。
 * <p>组件名/类型与前端 {@code NexusAI.ConfigImportStats} 的值对象（{created,updated,skipped}）严格对齐，
 * 序列化 JSON 字节级一致。外层仍为 {@code Map<entity, ImportEntityStatVo>}（实体名为动态键·对应前端索引签名）。
 *
 * @author nexusai
 */
public record ImportEntityStatVo(int created, int updated, int skipped) {
}
