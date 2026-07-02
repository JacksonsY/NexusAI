package com.nexusai.persistence.log.clickhouse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexusai.persistence.log.clickhouse.NaiRequestLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 请求日志 Mapper（nai_request_log·ClickHouse driver 私有）。
 * <p>分位/时序用 <b>ClickHouse 原生</b> {@code quantile()} / {@code toStartOfHour()}（MyBatis-Plus Wrapper 抽象不了·裸 @Select）；
 * 简单查询（page/findByRequestId/状态码分组/avg-max）走 BaseMapper + Wrapper。CH 别名大小写敏感且保留（无 PG 折叠问题）。
 *
 * @author nexusai
 */
@Mapper
public interface NaiRequestLogChMapper extends BaseMapper<NaiRequestLog> {

    /** 清理（ClickHouse 异步 mutation） */
    @Update("alter table nai_request_log delete where create_dt < #{cutoff}")
    void deleteBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 批量插入（<b>单条多值 INSERT</b>·一次写入落一个 part）：规避 ClickHouse 逐行 insert 触发的 part 爆炸
     * （TOO_MANY_PARTS）。列表按固定列全量列出（不走 MyBatis-Plus 的 null 省略），故 String/LowCardinality
     * 等非 Nullable 列须由 store 侧保证非 null（见 {@code ClickHouseRequestLogStore#toPo}）。id 列由 CH DEFAULT 0 承接不写。
     */
    @Insert("<script>"
            + "insert into nai_request_log (request_id, trace_id, project_code, workspace_id, channel_id, key_id, "
            + "model, target_model, modality, stream_mode, api_format, endpoint_api_format, "
            + "prompt_tokens, completion_tokens, cache_tokens, cache_creation_tokens, total_tokens, cost_usd, "
            + "status, http_status, error_code, retry_count, fallback_count, usage_source, "
            + "ttft_ms, duration_ms, client_ip, user_agent, client_family, prompt_hash, create_dt) values "
            + "<foreach collection='list' item='it' separator=','>"
            + "(#{it.requestId}, #{it.traceId}, #{it.projectCode}, #{it.workspaceId}, #{it.channelId}, #{it.keyId}, "
            + "#{it.model}, #{it.targetModel}, #{it.modality}, #{it.streamMode}, #{it.apiFormat}, #{it.endpointApiFormat}, "
            + "#{it.promptTokens}, #{it.completionTokens}, #{it.cacheTokens}, #{it.cacheCreationTokens}, #{it.totalTokens}, #{it.costUsd}, "
            + "#{it.status}, #{it.httpStatus}, #{it.errorCode}, #{it.retryCount}, #{it.fallbackCount}, #{it.usageSource}, "
            + "#{it.ttftMs}, #{it.durationMs}, #{it.clientIp}, #{it.userAgent}, #{it.clientFamily}, #{it.promptHash}, #{it.createDt})"
            + "</foreach>"
            + "</script>")
    void insertBatch(@Param("list") List<NaiRequestLog> list);

}
