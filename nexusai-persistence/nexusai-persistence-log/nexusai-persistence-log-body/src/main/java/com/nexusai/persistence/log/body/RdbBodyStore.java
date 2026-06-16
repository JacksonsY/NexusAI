package com.nexusai.persistence.log.body;

import com.nexusai.persistence.log.RequestBodyBlob;
import com.nexusai.persistence.log.RequestBodyStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 请求体独立库存储（{@code nexusai.log.body.store=rdb}）：
 * 正文落<b>第三个独立库</b> {@code nai_request_body}（与业务库/日志库均物理分离），按 {@code requestId} 关联日志主表。
 *
 * <p>独立 {@link HikariDataSource} + {@link JdbcTemplate}（遵循「不暴露 DataSource bean·不污染主源」纪律）；
 * 正文 {@code gzip} 压缩存（{@code req_gzip}/{@code resp_gzip} BLOB）。建表 DDL 见 script/sql/。
 *
 * @author nexusai
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(RequestBodyProperties.class)
@ConditionalOnProperty(prefix = "nexusai.log.body", name = "store", havingValue = "rdb")
public class RdbBodyStore implements RequestBodyStore {

    /** gzip 魔数首字节（0x1f）：解码前据此判断是否压缩落库。 */
    private static final int GZIP_MAGIC_0 = 0x1f;
    /** gzip 魔数次字节（0x8b）。 */
    private static final int GZIP_MAGIC_1 = 0x8b;

    private final RequestBodyProperties props;

    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;

    @PostConstruct
    public void init() {
        RequestBodyProperties.DataSource d = props.getDatasource();
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(d.getUrl());
        c.setUsername(d.getUsername());
        c.setPassword(d.getPassword());
        if (d.getDriverClassName() != null && !d.getDriverClassName().isBlank()) {
            c.setDriverClassName(d.getDriverClassName());
        }
        c.setMaximumPoolSize(Math.max(1, d.getPoolSize()));
        c.setPoolName("nexusai-body-pool");
        this.dataSource = new HikariDataSource(c);
        this.jdbc = new JdbcTemplate(dataSource);
        log.info("请求体独立库已装配 url={} gzip={} collect={}", d.getUrl(), props.isGzip(), props.isCollect());
    }

    @PreDestroy
    public void destroy() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    @Override
    public String save(String requestId, byte[] requestBody, byte[] responseBody) {
        if (requestId == null) {
            return null;
        }
        byte[] req = encode(requestBody);
        byte[] resp = encode(responseBody);
        jdbc.update("INSERT INTO nai_request_body(request_id, req_body, resp_body, create_dt) VALUES (?,?,?,?)",
                requestId, req, resp, LocalDateTime.now());
        return requestId;
    }

    @Override
    public RequestBodyBlob load(String requestId) {
        if (requestId == null) {
            return null;
        }
        List<RequestBodyBlob> rows = jdbc.query(
                "SELECT request_id, req_body, resp_body, create_dt FROM nai_request_body WHERE request_id = ?",
                (rs, n) -> new RequestBodyBlob(
                        rs.getString("request_id"),
                        decode(rs.getBytes("req_body")),
                        decode(rs.getBytes("resp_body")),
                        rs.getObject("create_dt", LocalDateTime.class)),
                requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public boolean isCollecting() {
        return props.isCollect();
    }

    /** gzip 编码（关闭压缩时原样返回）。 */
    private byte[] encode(byte[] raw) {
        if (raw == null) {
            return null;
        }
        if (!props.isGzip()) {
            return raw;
        }
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(raw);
            gz.finish();
            return bos.toByteArray();
        } catch (Exception e) {
            log.warn("正文 gzip 压缩失败，存原文 requestId-bytes={}", raw.length, e);
            return raw;
        }
    }

    /** gzip 解码（非 gzip 魔数则原样返回·正文采集存储无压缩写入时原样落库）。 */
    private static byte[] decode(byte[] stored) {
        if (stored == null || stored.length < 2) {
            return stored;
        }
        boolean gzipped = (stored[0] == (byte) GZIP_MAGIC_0) && (stored[1] == (byte) GZIP_MAGIC_1);
        if (!gzipped) {
            return stored;
        }
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(stored));
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            gz.transferTo(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            // 失败回退原文（debug/审计读路径非关键·不阻断追溯）：仍记 warn 以暴露存储损坏
            log.warn("正文 gzip 解码失败，返回原始字节 storedBytes={}", stored.length, e);
            return stored;
        }
    }
}
