package com.nexusai.persistence.log.stat;

import java.time.LocalDateTime;

/**
 * 请求间隔时间线原始行（缓存亲和性时间线数据源）：
 * 近 N 小时请求的（项目, 模型, 时间）轻量三元组，按（project_code, create_dt）升序返回，
 * 相邻间隔（分钟）由上层 Java 端按项目分组差分计算。
 *
 * @param projectCode 项目编码（分组键）
 * @param model    请求模型（散点 tooltip 用）
 * @param createDt 请求时间
 * @author nexusai
 */
public record IntervalRow(String projectCode, String model, LocalDateTime createDt) {
}
