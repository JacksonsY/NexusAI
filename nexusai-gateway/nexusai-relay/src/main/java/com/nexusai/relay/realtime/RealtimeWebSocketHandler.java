package com.nexusai.relay.realtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.nexusai.auth.ApiKeyAuthService;
import com.nexusai.billing.crypto.CredentialCipher;
import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.common.util.ClientIp;
import com.nexusai.common.util.IpMatcher;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.persistence.po.NaiToken;
import com.nexusai.router.RouteResult;
import com.nexusai.router.RouteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Realtime WebSocket 入站桥接（{@code /v1/realtime}）：客户端 WS ↔ 上游 WS 双向帧透传（文本/二进制音频均原样转发）。
 * <p>握手鉴权：sk- 取自 {@code Authorization: Bearer} / {@code ?key=} / 子协议 {@code openai-insecure-api-key.<key>}；
 * 按 {@code ?model=} 两级路由选 realtime 渠道+Key，解密上游凭证后用 {@link ReactorNettyWebSocketClient} 拨号
 * {@code wss://{baseUrl}/realtime?model=...}（OpenAI-Beta: realtime=v1）。任一端关闭即拆桥并释放并发槽。
 * <p><b>计费</b>（{@link RealtimeBilling}）：连接准入查钱包余额&gt;0（杜绝零余额白嫖）；逐 {@code response.done} 后付费扣账
 * + 发 UsageEvent；余额耗尽即拆桥。
 *
 * @author nexusai
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RealtimeWebSocketHandler implements WebSocketHandler {

    private final ApiKeyAuthService authService;
    private final RouteService routeService;
    private final CredentialCipher cipher;
    private final RealtimeBilling realtimeBilling;
    private final ReactorNettyWebSocketClient upstreamClient = new ReactorNettyWebSocketClient();

    @Override
    public Mono<Void> handle(WebSocketSession client) {
        // 🔴 握手期鉴权(JDBC)/IP&模型校验/余额查询(Redis)/路由(DB)/凭证解密(AES) 全是阻塞调用——
        // offload 到 boundedElastic，绝不在 Netty 事件循环线程上执行，否则高并发握手会饿死事件循环、拖垮全部反应式流量。
        return Mono.fromCallable(() -> admit(client))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(a -> {
                    if (a.reject() != null) {
                        return client.close(a.reject());
                    }
                    return upstreamClient.execute(a.upstreamUri(), a.upHeaders(),
                                    upstream -> bridge(client, upstream, a.token(), a.model(), a.route()))
                            .onErrorResume(e -> {
                                log.warn("Realtime 上游 WS 连接失败 model={}: {}", a.model(), e.toString());
                                return client.close(new CloseStatus(4502, "上游连接失败"));
                            })
                            .doFinally(sig -> routeService.releaseSlot(a.route()));
                });
    }

    /**
     * 握手准入（阻塞·在 boundedElastic 上执行）：鉴权 → 取 model → 与 HTTP relay 链对齐的入站闸门
     * → 两级路由选渠道+Key → 解密上游凭证。realtime 不经 {@code RelayFilter} 链，故此处显式补齐
     * {@link com.nexusai.relay.filter.TokenAccessFilter}（IP 白名单）/{@link com.nexusai.relay.filter.ModelPermissionFilter}
     * （模型白名单）的同口径校验，杜绝 WS 入口绕过模型/IP 管控直连昂贵 realtime 模型。
     */
    private Admission admit(WebSocketSession client) {
        URI uri = client.getHandshakeInfo().getUri();
        HttpHeaders headers = client.getHandshakeInfo().getHeaders();

        String auth = resolveClientKey(headers, uri.getQuery());
        NaiToken token;
        try {
            token = authService.authenticate(auth);
        } catch (RuntimeException e) {
            return Admission.rejected(new CloseStatus(4001, "鉴权失败"));
        }
        String model = queryParam(uri.getQuery(), "model");
        if (model == null || model.isBlank()) {
            return Admission.rejected(new CloseStatus(4000, "缺少 model 查询参数"));
        }
        // ① 来源 IP 白名单（nai_token.allow_ips·与 TokenAccessFilter 同口径）
        if (!ipAllowed(token, resolveClientIp(client, headers))) {
            return Admission.rejected(new CloseStatus(4003, "来源 IP 不允许"));
        }
        // ② 模型权限白名单（nai_token.model_limits·与 ModelPermissionFilter 同口径）
        if (!modelAllowed(token, model)) {
            return Admission.rejected(new CloseStatus(4003, "该 Key 无权访问模型: " + model));
        }
        // ③ 未建价闸门（deny-unpriced·与 HTTP/任务路径同口径）：未建价模型拒连，杜绝免费流式 realtime 音频白嫖
        if (!realtimeBilling.priced(model)) {
            return Admission.rejected(new CloseStatus(4003, "模型未建价，拒绝计费: " + model));
        }
        // ④ 三级钱包余额>0（项目池/工作区/子限额·杜绝零余额账户白嫖 realtime 音频流量）
        if (!realtimeBilling.hasBalance(token)) {
            return Admission.rejected(new CloseStatus(4003, "余额不足"));
        }

        String group = token.getGroupCode() == null ? GatewayConstants.DEFAULT_GROUP : token.getGroupCode();
        RouteResult route;
        try {
            route = routeService.route(group, model, null, Set.of(), Set.of());
        } catch (RuntimeException e) {
            return Admission.rejected(new CloseStatus(4002, "无可用渠道"));
        }
        try {
            String upstreamKey = cipher.decrypt(route.key().getCredential());
            URI upstreamUri = buildWsUri(route.channel().getBaseUrl(), route.targetModel());
            HttpHeaders upHeaders = new HttpHeaders();
            upHeaders.set(GatewayConstants.AUTH_HEADER, GatewayConstants.BEARER_PREFIX + upstreamKey);
            upHeaders.set("OpenAI-Beta", "realtime=v1");
            return Admission.ok(token, model, route, upstreamUri, upHeaders);
        } catch (RuntimeException e) {
            routeService.releaseSlot(route);   // 释放已占并发槽，避免准入末段异常泄漏
            return Admission.rejected(new CloseStatus(4500, "上游凭证解密失败"));
        }
    }

    /** 握手准入结果：{@code reject!=null} 表示拒绝（携带关闭码）；否则携带路由/凭证供建桥。 */
    private record Admission(CloseStatus reject, NaiToken token, String model,
                             RouteResult route, URI upstreamUri, HttpHeaders upHeaders) {
        static Admission rejected(CloseStatus status) {
            return new Admission(status, null, null, null, null, null);
        }

        static Admission ok(NaiToken token, String model, RouteResult route, URI upstreamUri, HttpHeaders upHeaders) {
            return new Admission(null, token, model, route, upstreamUri, upHeaders);
        }
    }

    /** 解析真实客户端 IP（经 {@link ClientIp}·仅信任可信代理的 XFF·防伪造绕过 IP 白名单）。 */
    private static String resolveClientIp(WebSocketSession client, HttpHeaders headers) {
        var remote = client.getHandshakeInfo().getRemoteAddress();
        String peer = remote == null ? ""
                : remote.getAddress() != null ? remote.getAddress().getHostAddress() : remote.getHostString();
        return ClientIp.resolve(peer, headers.getFirst("X-Forwarded-For"));
    }

    /** 来源 IP 白名单校验（与 TokenAccessFilter 同口径·空=不限制）。 */
    private static boolean ipAllowed(NaiToken token, String ip) {
        String raw = token.getAllowIps();
        if (raw == null || raw.isBlank() || ip == null || ip.isBlank()) {
            return true;
        }
        List<String> allowed = parseList(raw);
        return allowed.isEmpty() || IpMatcher.inWhitelist(ip, allowed);
    }

    /** 模型权限白名单校验（与 ModelPermissionFilter 同口径·空/解析失败=放行 fail-open）。 */
    private static boolean modelAllowed(NaiToken token, String model) {
        String json = token.getModelLimits();
        if (json == null || json.isBlank()) {
            return true;
        }
        try {
            List<String> allowed = JsonUtils.parse(json, new TypeReference<List<String>>() {
            });
            return allowed == null || allowed.isEmpty() || allowed.contains(model);
        } catch (Exception e) {
            return true;
        }
    }

    /** allow_ips 兼容 JSON 数组与逗号分隔两种存法（与 TokenAccessFilter.parseIps 同口径）。 */
    private static List<String> parseList(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("[")) {
            try {
                return JsonUtils.parse(trimmed, new TypeReference<List<String>>() {
                });
            } catch (Exception e) {
                return List.of();
            }
        }
        return Arrays.stream(trimmed.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * 双向帧桥接：任一端结束即拆桥（保留帧类型：text/binary/ping/pong 原样转发·音频二进制不丢）。
     * 上游→客户端方向旁路计费：text 帧交 {@link RealtimeBilling} 解析 response.done 后付费（不改帧、不阻塞转发）。
     */
    private Mono<Void> bridge(WebSocketSession client, WebSocketSession upstream,
                              NaiToken token, String model, RouteResult route) {
        Mono<Void> c2u = upstream.send(client.receive().map(msg -> copy(msg, upstream)));
        Mono<Void> u2c = client.send(upstream.receive().map(msg -> copyAndBill(msg, client, token, model, route)));
        return Mono.firstWithSignal(c2u, u2c);
    }

    /** 把一端收到的帧按原类型复制到另一端会话（payload 拷贝到目标 bufferFactory）。 */
    private static WebSocketMessage copy(WebSocketMessage msg, WebSocketSession to) {
        return new WebSocketMessage(msg.getType(), to.bufferFactory().wrap(readBytes(msg)));
    }

    /** 上游→客户端帧：text 帧旁路计费（一次读字节·复用于转发），其余原样转发。 */
    private WebSocketMessage copyAndBill(WebSocketMessage msg, WebSocketSession client,
                                         NaiToken token, String model, RouteResult route) {
        byte[] bytes = readBytes(msg);
        if (msg.getType() == WebSocketMessage.Type.TEXT) {
            realtimeBilling.onUpstreamText(new String(bytes, StandardCharsets.UTF_8), token, model, route, client);
        }
        return new WebSocketMessage(msg.getType(), client.bufferFactory().wrap(bytes));
    }

    /** 读出帧 payload 字节（消费一次·调用方据此重建帧到目标会话）。 */
    private static byte[] readBytes(WebSocketMessage msg) {
        var payload = msg.getPayload();
        byte[] bytes = new byte[payload.readableByteCount()];
        payload.read(bytes);
        return bytes;
    }

    /** 取客户端 sk-：Authorization Bearer → ?key= → 子协议 openai-insecure-api-key.<key>。 */
    private static String resolveClientKey(HttpHeaders headers, String query) {
        String auth = headers.getFirst(GatewayConstants.AUTH_HEADER);
        if (auth != null && auth.startsWith(GatewayConstants.BEARER_PREFIX)) {
            return auth;
        }
        String key = queryParam(query, "key");
        if (key != null && !key.isBlank()) {
            return GatewayConstants.BEARER_PREFIX + key;
        }
        List<String> protocols = headers.get("Sec-WebSocket-Protocol");
        if (protocols != null) {
            for (String line : protocols) {
                for (String p : line.split(",")) {
                    String t = p.trim();
                    if (t.startsWith("openai-insecure-api-key.")) {
                        return GatewayConstants.BEARER_PREFIX + t.substring("openai-insecure-api-key.".length());
                    }
                }
            }
        }
        return auth;
    }

    /** baseUrl(http/https) → ws/wss + /realtime?model=。 */
    private static URI buildWsUri(String baseUrl, String model) {
        String b = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        if (b.startsWith("https://")) {
            b = "wss://" + b.substring("https://".length());
        } else if (b.startsWith("http://")) {
            b = "ws://" + b.substring("http://".length());
        }
        return URI.create(b + "/realtime?model=" + URLDecoder.decode(model, StandardCharsets.UTF_8));
    }

    private static String queryParam(String query, String name) {
        if (query == null) {
            return null;
        }
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(name)) {
                return URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
