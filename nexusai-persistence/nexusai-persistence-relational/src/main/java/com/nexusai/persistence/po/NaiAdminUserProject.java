package com.nexusai.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 运维账号 × 项目授权（项目运维身份的行级授权·借鉴 snail-job {@code sj_system_user_permission} 的 namespace 授权）。
 * <p>一行表示「该运维账号被授权管理该 project_code」。超管不需要登记（全项目）；仅 {@link NaiAdminUser#ROLE_USER} 用此表收敛可见项目集。
 *
 * @author nexusai
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nai_admin_user_project")
public class NaiAdminUserProject extends BasePO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联 {@code nai_admin_user.id}。 */
    private Long adminUserId;

    /** 被授权的项目码（关联 {@code nai_project.project_code}）。 */
    private String projectCode;
}
