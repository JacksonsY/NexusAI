package com.nexusai.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexusai.persistence.po.NaiAdminUserProject;
import org.apache.ibatis.annotations.Mapper;

/**
 * NaiAdminUserProject Mapper（运维账号×项目授权·MyBatis-Plus BaseMapper）。
 *
 * @author nexusai
 */
@Mapper
public interface NaiAdminUserProjectMapper extends BaseMapper<NaiAdminUserProject> {
}
