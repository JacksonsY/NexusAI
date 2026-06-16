package com.nexusai.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 网关实例节点（多实例集群视图）：每个网关进程守护线程心跳 upsert 一行，
 * {@code update_dt} 即末次心跳；控制台「代理节点」页按心跳新鲜度判在线/离线。
 *
 * @author nexusai
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nai_node")
public class NaiNode extends BasePO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 节点唯一标识（host:port·进程内稳定·唯一键 upsert 锚点） */
    private String nodeId;

    private String host;

    private Integer port;

    private String version;

    /** 进程启动时间（epoch ms） */
    private Long startedAt;

    /** leader / worker（Redis leader 标记的持有者为 leader·随心跳回写） */
    private String role;

    /** 当前在途透传请求数（ActiveRequestRegistry 快照） */
    private Integer activeRequests;

    /** 进程 CPU 使用率（%·null=不可采） */
    private Double cpuPercent;

    private Integer heapUsedMb;
    private Integer heapMaxMb;

    /** 部署标签（JSON·可选） */
    private String labels;
}
