package com.nexusai.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 控制面审计日志：记录 /admin/** 写操作与登录成败，
 * 用于安全事件追踪与变更回溯。仅管理面事件——流量面请求记录在 request_log（observe）。
 *
 * @author nexusai
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nai_audit_log")
public class NaiAuditLog extends BasePO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作者：admin / project:{code} / 系统 */
    private String actor;

    /** 事件类型：admin_login_success / config_changed / channel_created ... */
    private String eventType;

    private String description;

    /** 请求路径（登录类事件可空） */
    private String path;

    /** HTTP 方法 */
    private String method;

    /** 来源 IP */
    private String ip;

    private Integer statusCode;

    private String errorMessage;
}
