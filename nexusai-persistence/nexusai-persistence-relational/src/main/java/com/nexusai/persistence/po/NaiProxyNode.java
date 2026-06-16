package com.nexusai.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 出口代理节点（手动 HTTP/SOCKS5 代理）：上游透传/渠道探测的出站代理；
 * 渠道 setting.proxyNodeId 或全局 system_proxy_node_id 绑定。状态由进程内探测任务维护。
 *
 * @author nexusai
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nai_proxy_node")
public class NaiProxyNode extends BasePO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    /** http://host:port 或 socks5://host:port（不含账密，账密单列加密存） */
    private String proxyUrl;

    private String username;

    /** 代理密码（AES-256-GCM 密文） */
    private String passwordEnc;

    private String region;

    /** online / offline（探测任务维护） */
    private String status;

    /** 经此代理的透传请求累计（relay 侧计数定期落库） */
    private Long totalRequests;
    private Long failedRequests;

    /** 探测延迟 EMA（毫秒） */
    private Double avgLatencyMs;

    private LocalDateTime lastProbeAt;
    private LocalDateTime lastOkAt;
}
