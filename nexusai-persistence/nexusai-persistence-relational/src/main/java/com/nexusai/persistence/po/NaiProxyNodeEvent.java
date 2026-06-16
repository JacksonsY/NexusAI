package com.nexusai.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 代理节点连接事件（connected/disconnected/error·探测任务状态翻转时写入）。
 *
 * @author nexusai
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nai_proxy_node_event")
public class NaiProxyNodeEvent extends BasePO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long nodeId;

    /** connected / disconnected / error */
    private String eventType;

    private String detail;
}
