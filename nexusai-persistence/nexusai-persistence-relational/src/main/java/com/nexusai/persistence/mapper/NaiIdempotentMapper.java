package com.nexusai.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexusai.persistence.po.NaiIdempotent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 幂等记录 Mapper（nai_idempotent）。
 *
 * @author nexusai
 */
@Mapper
public interface NaiIdempotentMapper extends BaseMapper<NaiIdempotent> {
}
