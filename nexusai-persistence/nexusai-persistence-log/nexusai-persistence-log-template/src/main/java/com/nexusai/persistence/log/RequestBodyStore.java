package com.nexusai.persistence.log;

/**
 * 请求/响应正文分离存储 SPI（正文与日志主表物理分离·存储无关）。
 * <p>AI 日志最大的部分是 prompt/response 正文（动辄几十 KB~MB），与元数据行（耗时/token/状态码）的访问模式完全不同：
 * 元数据要频繁聚合扫描、正文只在「追溯单条」时读。故正文<b>物理分离</b>到独立库/对象存储，与日志主表用 {@code requestId} 关联。
 * <p>默认 {@code hash} 档（{@code NoopBodyStore}）不存正文，仅日志主表存 sha256；{@code rdb}/{@code oss} 档按需开启。
 * 流式/大模态强制跳过正文采集（响应体大小不可控）。
 *
 * @author nexusai
 */
public interface RequestBodyStore {

    /**
     * 存正文。{@code hash} 档返回 null（不存）；{@code rdb}/{@code oss} 返回引用（自增 id / objectKey）。
     *
     * @param requestId    关联日志主表
     * @param requestBody  请求正文（可空）
     * @param responseBody 响应正文（可空·流式通常不存）
     */
    String save(String requestId, byte[] requestBody, byte[] responseBody);

    /** 按 requestId 取回正文（debug/审计/重放）；无或未启用返回 null。 */
    RequestBodyBlob load(String requestId);

    /** 是否启用正文采集（hash 档为 false·调用方据此跳过昂贵的 body 缓冲）。 */
    boolean isCollecting();
}
