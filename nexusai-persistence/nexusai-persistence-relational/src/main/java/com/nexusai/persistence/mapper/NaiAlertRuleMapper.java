package com.nexusai.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexusai.persistence.po.NaiAlertRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 告警规则 Mapper。
 *
 * @author nexusai
 */
@Mapper
public interface NaiAlertRuleMapper extends BaseMapper<NaiAlertRule> {
}
