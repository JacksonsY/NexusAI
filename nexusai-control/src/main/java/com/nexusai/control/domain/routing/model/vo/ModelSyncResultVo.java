package com.nexusai.control.domain.routing.model.vo;

import java.util.List;

/**
 * 模型上游元数据同步执行结果（建缺失模型 + 覆盖字段）。
 * <p>{@code created}/{@code updated} 为本次新建/更新数（int·序列化为 number），
 * {@code skipped} 为跳过的模型 key 列表（上游无元数据/已存在/本地锁定）。
 *
 * @author nexusai
 */
public record ModelSyncResultVo(int created, int updated, List<String> skipped) {
}
