package com.nexusai.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexusai.persistence.po.NaiAlertEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 告警事件 Mapper。
 *
 * @author nexusai
 */
@Mapper
public interface NaiAlertEventMapper extends BaseMapper<NaiAlertEvent> {
}
