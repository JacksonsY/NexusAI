package com.nexusai.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 控制台运维账号（人类登录主体·借鉴 snail-ai {@code sai_user} / snail-job {@code sj_system_user}）。
 * <p>nexusai 单运维台演进出的两身份模型：{@link #role} = 2 超管（全权）/ 1 项目运维（仅被授权项目的自助端点·
 * 授权关系见 {@code nai_admin_user_project}）。口令经 {@code PasswordHasher} 加盐 PBKDF2 存储·明文永不落库。
 *
 * <p>对位关系：nexusai 的 <b>project</b> = snail-job 的 <b>namespace</b>——项目运维按项目授权，等同 snail-job 普通用户按命名空间授权。
 *
 * @author nexusai
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nai_admin_user")
public class NaiAdminUser extends BasePO {

    /** 角色：项目运维（仅被授权项目的自助端点）。 */
    public static final int ROLE_USER = 1;
    /** 角色：超级管理员（全部 /admin·渠道/路由/项目/全局配置）。 */
    public static final int ROLE_ADMIN = 2;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 登录账号（唯一）。 */
    private String username;

    /** 口令哈希串（PBKDF2·{@code pbkdf2$iter$salt$hash}）。 */
    private String password;

    /** 角色：1 项目运维 / 2 超管。 */
    private Integer role;

    /** 状态：1 启用 / 0 停用。 */
    private Integer status;
}
