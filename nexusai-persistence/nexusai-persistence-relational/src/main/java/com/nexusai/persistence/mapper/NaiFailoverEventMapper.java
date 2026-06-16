package com.nexusai.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexusai.persistence.po.NaiFailoverEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * NaiFailoverEvent Mapper（故障转移/重试事件审计·对抗验证 H7）。
 *
 * @author nexusai
 */
@Mapper
public interface NaiFailoverEventMapper extends BaseMapper<NaiFailoverEvent> {
}
