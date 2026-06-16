package com.nexusai.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexusai.persistence.po.NaiAuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审计日志 Mapper。
 *
 * @author nexusai
 */
@Mapper
public interface NaiAuditLogMapper extends BaseMapper<NaiAuditLog> {
}
