package com.nexusai.persistence.log.pg.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexusai.persistence.log.pg.NaiRequestLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 请求日志 Mapper（nai_request_log·PostgreSQL 存储 driver 私有）。
 * <p>类名带 {@code Pg} 后缀与 log-mysql 的 {@code NaiRequestLogMapper} 区分 Spring bean 名（默认按简单类名生成·避免冲突）。
 * <p>分位/分桶用 <b>PG 方言</b>（区别于 log-mysql 的 {@code PERCENT_RANK}/{@code DATE_FORMAT}）：
 * 分位用 {@code percentile_cont(...) WITHIN GROUP}，分桶用 {@code to_char(...,'YYYY-MM-DD HH24:00')}。
 *
 * @author nexusai
 */
@Mapper
public interface NaiRequestLogPgMapper extends BaseMapper<NaiRequestLog> {

    /** 分批清理（PG 无 DELETE LIMIT·走 id 子查询） */
    @Delete("""
            delete from nai_request_log where id in (
              select id from nai_request_log where create_dt < #{cutoff} limit #{limit})
            """)
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff,
                     @Param("limit") int limit);

    /**
     * 批量插入（单条多值 INSERT·一次往返落整批）：替代逐行 insert 的写入吞吐上限。
     * <p>不经 {@code mapper.insert} 的 {@code ASSIGN_ID}/{@code MetaObjectHandler}，故 store 侧须先补齐主键 id
     * 与 create_dt（见 {@code PgRequestLogStore#saveBatch}）；update_dt 由 DDL 默认值承接不写。
     */
    @Insert("<script>"
            + "insert into nai_request_log (id, request_id, trace_id, project_code, workspace_id, channel_id, key_id, "
            + "model, target_model, modality, stream_mode, api_format, endpoint_api_format, "
            + "prompt_tokens, completion_tokens, cache_tokens, cache_creation_tokens, total_tokens, cost_usd, "
            + "status, http_status, error_code, retry_count, fallback_count, usage_source, "
            + "ttft_ms, duration_ms, client_ip, user_agent, client_family, prompt_hash, create_dt) values "
            + "<foreach collection='list' item='it' separator=','>"
            + "(#{it.id}, #{it.requestId}, #{it.traceId}, #{it.projectCode}, #{it.workspaceId}, #{it.channelId}, #{it.keyId}, "
            + "#{it.model}, #{it.targetModel}, #{it.modality}, #{it.streamMode}, #{it.apiFormat}, #{it.endpointApiFormat}, "
            + "#{it.promptTokens}, #{it.completionTokens}, #{it.cacheTokens}, #{it.cacheCreationTokens}, #{it.totalTokens}, #{it.costUsd}, "
            + "#{it.status}, #{it.httpStatus}, #{it.errorCode}, #{it.retryCount}, #{it.fallbackCount}, #{it.usageSource}, "
            + "#{it.ttftMs}, #{it.durationMs}, #{it.clientIp}, #{it.userAgent}, #{it.clientFamily}, #{it.promptHash}, #{it.createDt})"
            + "</foreach>"
            + "</script>")
    void insertBatch(@Param("list") List<NaiRequestLog> list);

}
