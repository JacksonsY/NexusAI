package com.nexusai.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexusai.persistence.po.NaiAdminUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * NaiAdminUser Mapper（控制台运维账号·MyBatis-Plus BaseMapper）。
 *
 * @author nexusai
 */
@Mapper
public interface NaiAdminUserMapper extends BaseMapper<NaiAdminUser> {
}
