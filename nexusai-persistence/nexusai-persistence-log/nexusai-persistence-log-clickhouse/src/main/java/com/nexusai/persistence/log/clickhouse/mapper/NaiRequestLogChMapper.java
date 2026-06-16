package com.nexusai.persistence.log.clickhouse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexusai.persistence.log.clickhouse.NaiRequestLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

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

}
