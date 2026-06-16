package com.nexusai.control.domain.routing.proxynode.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexusai.billing.crypto.CredentialCipher;
import com.nexusai.control.domain.platform.system.service.SystemConfigService;
import com.nexusai.control.domain.routing.proxynode.vo.ProxyNodeMetricsVo;
import com.nexusai.control.domain.routing.proxynode.vo.ProxyTestResultVo;
import com.nexusai.persistence.mapper.NaiProxyNodeEventMapper;
import com.nexusai.persistence.mapper.NaiProxyNodeMapper;
import com.nexusai.persistence.mapper.NaiProxyNodeMetricMapper;
import com.nexusai.persistence.po.NaiProxyNode;
import com.nexusai.persistence.po.NaiProxyNodeEvent;
import com.nexusai.persistence.po.NaiProxyNodeMetric;
import io.netty.channel.ChannelOption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 出口代理节点服务（手动节点）：CRUD + 连通性探测 + 指标/事件查询。
 * <p>探测 = 经代理 GET probe-url（默认 cloudflare trace·可解析出口 IP）；状态翻转由
 * {@link com.nexusai.control.domain.routing.proxynode.job.ProxyNodeProbeJob} 周期探测维护。
 *
 * @author nexusai
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProxyNodeService {

    /** 全局默认代理配置键（系统设置·网络代理 section） */
    public static final String CFG_SYSTEM_PROXY_NODE = "system_proxy_node_id";

    /** 事件 detail 入库截断长度 */
    private static final int EVENT_DETAIL_MAX_LEN = 480;
    /** 事件查询条数下/上限 */
    private static final int EVENTS_LIMIT_MIN = 1;
    private static final int EVENTS_LIMIT_MAX = 200;
    /** 指标查询窗口（小时）下/上限 */
    private static final int METRICS_HOURS_MIN = 1;
    private static final int METRICS_HOURS_MAX = 168;
    /** 探测超时余量（秒·block 比 responseTimeout 多等的缓冲） */
    private static final long PROBE_BLOCK_BUFFER_SECONDS = 2L;
    private static final int SOCKS_DEFAULT_PORT = 1080;
    private static final int HTTP_DEFAULT_PORT = 80;
    private static final int MILLIS_PER_SECOND = 1000;

    private final NaiProxyNodeMapper nodeMapper;
    private final NaiProxyNodeEventMapper eventMapper;
    private final NaiProxyNodeMetricMapper metricMapper;
    private final CredentialCipher cipher;
    private final SystemConfigService systemConfig;

    @Value("${nexusai.proxy.probe-url:https://www.cloudflare.com/cdn-cgi/trace}")
    private String probeUrl;

    @Value("${nexusai.proxy.probe-timeout-seconds:8}")
    private int probeTimeoutSeconds;

    public List<NaiProxyNode> list() {
        return nodeMapper.selectList(new LambdaQueryWrapper<NaiProxyNode>()
                .orderByDesc(NaiProxyNode::getCreateDt));
    }

    public NaiProxyNode get(long id) {
        return nodeMapper.selectById(id);
    }

    /** 编辑回显用明文密码（控制台 admin 会话内）。 */
    public String plainPassword(NaiProxyNode node) {
        if (node == null || node.getPasswordEnc() == null || node.getPasswordEnc().isBlank()) {
            return null;
        }
        try {
            return cipher.decrypt(node.getPasswordEnc());
        } catch (Exception e) {
            // 代理密码解密失败（主密钥轮转后旧密文不可解）：回显降级为空·不阻断编辑
            log.debug("代理节点 {} 密码解密失败，回显置空: {}", node.getId(), e.getMessage());
            return null;
        }
    }

    public NaiProxyNode create(String name, String proxyUrl, String username, String password, String region) {
        validateProxyUrl(proxyUrl);
        NaiProxyNode node = new NaiProxyNode();
        node.setName(name);
        node.setProxyUrl(stripCredentials(proxyUrl));
        node.setUsername(blankToNull(username));
        node.setPasswordEnc(password == null || password.isBlank() ? null : cipher.encrypt(password));
        node.setRegion(blankToNull(region));
        node.setStatus("offline");
        node.setTotalRequests(0L);
        node.setFailedRequests(0L);
        nodeMapper.insert(node);
        return node;
    }

    public NaiProxyNode update(long id, String name, String proxyUrl, String username, String password, String region) {
        NaiProxyNode node = nodeMapper.selectById(id);
        if (node == null) {
            throw new IllegalArgumentException("代理节点不存在: " + id);
        }
        validateProxyUrl(proxyUrl);
        node.setName(name);
        node.setProxyUrl(stripCredentials(proxyUrl));
        node.setUsername(blankToNull(username));
        if (password != null && !password.isBlank()) {
            node.setPasswordEnc(cipher.encrypt(password));
        }
        node.setRegion(blankToNull(region));
        nodeMapper.updateById(node);
        return node;
    }

    /** 删除节点；若其是全局默认代理则一并清除。 */
    public boolean delete(long id) {
        nodeMapper.deleteById(id);
        eventMapper.delete(new LambdaQueryWrapper<NaiProxyNodeEvent>().eq(NaiProxyNodeEvent::getNodeId, id));
        metricMapper.delete(new LambdaQueryWrapper<NaiProxyNodeMetric>().eq(NaiProxyNodeMetric::getNodeId, id));
        Long sys = systemConfig.getLong(CFG_SYSTEM_PROXY_NODE);
        if (sys != null && sys == id) {
            systemConfig.delete(CFG_SYSTEM_PROXY_NODE);
            return true;
        }
        return false;
    }

    /** 探测既有节点的连通性。 */
    public ProxyTestResultVo testNode(long id) {
        NaiProxyNode node = nodeMapper.selectById(id);
        if (node == null) {
            throw new IllegalArgumentException("代理节点不存在: " + id);
        }
        return probe(node.getProxyUrl(), node.getUsername(), plainPassword(node));
    }

    /** 添加前测试任意代理 URL。 */
    public ProxyTestResultVo probe(String proxyUrl, String username, String password) {
        validateProxyUrl(proxyUrl);
        long start = System.currentTimeMillis();
        try {
            String body = httpClientFor(stripCredentials(proxyUrl), username, password)
                    .responseTimeout(Duration.ofSeconds(probeTimeoutSeconds))
                    .get()
                    .uri(probeUrl)
                    .responseSingle((resp, bytes) -> bytes.asString().defaultIfEmpty(""))
                    .block(Duration.ofSeconds(probeTimeoutSeconds + PROBE_BLOCK_BUFFER_SECONDS));
            long latency = System.currentTimeMillis() - start;
            return new ProxyTestResultVo(probeUrl, probeTimeoutSeconds, true, latency, parseTraceIp(body), null);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            return new ProxyTestResultVo(probeUrl, probeTimeoutSeconds, false, latency, null, rootMessage(e));
        }
    }

    /** 24h 指标桶 + 汇总。 */
    public ProxyNodeMetricsVo metrics(long id, int hours) {
        LocalDateTime since = LocalDateTime.now()
                .minusHours(Math.max(METRICS_HOURS_MIN, Math.min(hours, METRICS_HOURS_MAX)));
        List<NaiProxyNodeMetric> rows = metricMapper.selectList(new LambdaQueryWrapper<NaiProxyNodeMetric>()
                .eq(NaiProxyNodeMetric::getNodeId, id)
                .ge(NaiProxyNodeMetric::getBucketStart, since)
                .orderByAsc(NaiProxyNodeMetric::getBucketStart));
        long samples = 0;
        long ok = 0;
        long latencySum = 0;
        int latencyMax = 0;
        long errors = 0;
        List<ProxyNodeMetricsVo.Bucket> items = new ArrayList<>(rows.size());
        for (NaiProxyNodeMetric m : rows) {
            samples += m.getSamples();
            ok += m.getOkSamples();
            latencySum += m.getLatencySumMs();
            latencyMax = Math.max(latencyMax, m.getLatencyMaxMs());
            errors += m.getErrors();
            items.add(new ProxyNodeMetricsVo.Bucket(
                    m.getBucketStart().toString(),
                    m.getSamples(),
                    m.getSamples() > 0 ? (double) m.getOkSamples() / m.getSamples() : null,
                    m.getOkSamples() > 0 ? (double) m.getLatencySumMs() / m.getOkSamples() : null,
                    m.getLatencyMaxMs(),
                    m.getErrors()));
        }
        ProxyNodeMetricsVo.Summary summary = new ProxyNodeMetricsVo.Summary(
                samples,
                ok,
                samples > 0 ? (double) ok / samples : null,
                ok > 0 ? (double) latencySum / ok : null,
                latencyMax,
                errors);
        return new ProxyNodeMetricsVo(items, summary);
    }

    public List<NaiProxyNodeEvent> events(long id, int limit) {
        return eventMapper.selectList(new LambdaQueryWrapper<NaiProxyNodeEvent>()
                .eq(NaiProxyNodeEvent::getNodeId, id)
                .orderByDesc(NaiProxyNodeEvent::getId)
                .last("limit " + Math.max(EVENTS_LIMIT_MIN, Math.min(limit, EVENTS_LIMIT_MAX))));
    }

    public void recordEvent(long nodeId, String type, String detail) {
        NaiProxyNodeEvent e = new NaiProxyNodeEvent();
        e.setNodeId(nodeId);
        e.setEventType(type);
        e.setDetail(detail == null ? null : detail.substring(0, Math.min(detail.length(), EVENT_DETAIL_MAX_LEN)));
        eventMapper.insert(e);
    }

    /** 构建经代理出站的 reactor-netty HttpClient（探测专用·透传面在 relay OutboundProxyClients）。 */
    public HttpClient httpClientFor(String proxyUrl, String username, String password) {
        URI uri = URI.create(proxyUrl);
        ProxyProvider.Proxy type = uri.getScheme().startsWith("socks")
                ? ProxyProvider.Proxy.SOCKS5 : ProxyProvider.Proxy.HTTP;
        int port = uri.getPort() > 0 ? uri.getPort()
                : (type == ProxyProvider.Proxy.SOCKS5 ? SOCKS_DEFAULT_PORT : HTTP_DEFAULT_PORT);
        return HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, probeTimeoutSeconds * MILLIS_PER_SECOND)
                .proxy(spec -> {
                    ProxyProvider.Builder b = spec.type(type).host(uri.getHost()).port(port);
                    if (username != null && !username.isBlank()) {
                        b.username(username).password(u -> password == null ? "" : password);
                    }
                });
    }

    static void validateProxyUrl(String proxyUrl) {
        URI uri;
        try {
            uri = URI.create(proxyUrl);
        } catch (Exception e) {
            throw new IllegalArgumentException("代理地址不是合法 URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https")
                || scheme.equals("socks5") || scheme.equals("socks5h"))) {
            throw new IllegalArgumentException("仅支持 http/https/socks5/socks5h 协议");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("代理地址缺少主机");
        }
    }

    /** URL 内嵌账密剥离（账密单列存储·密文）。 */
    static String stripCredentials(String proxyUrl) {
        URI uri = URI.create(proxyUrl);
        int port = uri.getPort();
        return uri.getScheme() + "://" + uri.getHost() + (port > 0 ? ":" + port : "");
    }

    private static String parseTraceIp(String body) {
        if (body == null) {
            return null;
        }
        for (String line : body.split("\n")) {
            if (line.startsWith("ip=")) {
                return line.substring(3).trim();
            }
        }
        return null;
    }

    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return msg == null || msg.isBlank() ? t.getClass().getSimpleName() : msg;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
