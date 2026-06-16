package com.nexusai.persistence.log.pg.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexusai.persistence.log.pg.NaiRequestLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

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

}
