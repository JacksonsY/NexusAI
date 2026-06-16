package com.nexusai.persistence.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 代理节点采样指标（1h 桶·复合主键 node_id+bucket_start·探测任务 upsert）：
 * 在线率条形图与延迟峰值数据源。
 *
 * @author nexusai
 */
@Data
@TableName("nai_proxy_node_metric")
public class NaiProxyNodeMetric implements Serializable {

    private Long nodeId;

    private LocalDateTime bucketStart;

    /** 桶内探测样本数 */
    private Integer samples;
    /** 桶内探测成功样本数 */
    private Integer okSamples;

    private Long latencySumMs;
    private Integer latencyMaxMs;

    /** 桶内探测失败次数 */
    private Integer errors;
}
