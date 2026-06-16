package com.nexusai.persistence.log.mysql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexusai.persistence.log.mysql.NaiRequestLog;
import com.nexusai.persistence.log.stat.PerfFilter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 请求日志 Mapper（nai_request_log·MySQL 存储 driver 私有）。
 *
 * @author nexusai
 */
@Mapper
public interface NaiRequestLogMapper extends BaseMapper<NaiRequestLog> {

    /**
     * 按渠道（或总体）P90/P99 分位（性能分析页）：{@code PERCENT_RANK()} 窗口近似（对齐既有口径）。
     * {@code col} 由 driver 写死（duration_ms/ttft_ms·非外部串）；filter 经参数绑定。
     */
    @Select("<script>"
            + "SELECT k, MAX(CASE WHEN pr &lt;= 0.90 THEN v END) AS p90, MAX(CASE WHEN pr &lt;= 0.99 THEN v END) AS p99 "
            + "FROM (SELECT "
            + "<choose><when test='grouped'>channel_id</when><otherwise>'_all'</otherwise></choose> AS k, "
            + "${col} AS v, "
            + "PERCENT_RANK() OVER (<if test='grouped'>PARTITION BY channel_id </if>ORDER BY ${col}) AS pr "
            + "FROM nai_request_log "
            + "WHERE create_dt &gt;= #{since} AND create_dt &lt;= #{until} AND ${col} &gt; 0 "
            + "<if test='grouped'>AND channel_id IS NOT NULL </if>"
            + "<if test='f.channelId != null'>AND channel_id = #{f.channelId} </if>"
            + "<if test='f.hasModel()'>AND model = #{f.model} </if>"
            + "<if test='f.hasModality()'>AND modality = #{f.modality} </if>"
            + "<if test='f.stream != null'>AND stream_mode = <choose><when test='f.stream'>1</when><otherwise>0</otherwise></choose> </if>"
            + ") t GROUP BY k"
            + "</script>")
    List<Map<String, Object>> selectChannelPercentiles(@Param("col") String col,
                                                       @Param("f") PerfFilter f,
                                                       @Param("since") LocalDateTime since,
                                                       @Param("until") LocalDateTime until,
                                                       @Param("grouped") boolean grouped);

    /**
     * 按日 P50/P90/P99 分位时序（性能分析页·{@code col}∈duration_ms|ttft_ms 由 driver 写死）。
     */
    @Select("SELECT d, MAX(CASE WHEN pr <= 0.50 THEN v END) AS p50, "
            + "MAX(CASE WHEN pr <= 0.90 THEN v END) AS p90, MAX(CASE WHEN pr <= 0.99 THEN v END) AS p99 "
            + "FROM (SELECT DATE_FORMAT(create_dt, '%Y-%m-%d') AS d, ${col} AS v, "
            + "PERCENT_RANK() OVER (PARTITION BY DATE_FORMAT(create_dt, '%Y-%m-%d') ORDER BY ${col}) AS pr "
            + "FROM nai_request_log WHERE create_dt >= #{since} AND create_dt <= #{until} AND ${col} > 0) t "
            + "GROUP BY d ORDER BY d")
    List<Map<String, Object>> selectDailyPercentiles(@Param("col") String col,
                                                     @Param("since") LocalDateTime since,
                                                     @Param("until") LocalDateTime until);
}
