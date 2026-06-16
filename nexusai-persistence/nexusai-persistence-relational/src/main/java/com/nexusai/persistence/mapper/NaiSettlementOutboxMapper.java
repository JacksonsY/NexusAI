package com.nexusai.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexusai.persistence.po.NaiSettlementOutbox;
import org.apache.ibatis.annotations.Mapper;

/**
 * 结算补偿 outbox Mapper（nai_settlement_outbox）。
 *
 * @author nexusai
 */
@Mapper
public interface NaiSettlementOutboxMapper extends BaseMapper<NaiSettlementOutbox> {
}
