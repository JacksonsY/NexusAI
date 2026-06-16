package com.nexusai.control.domain.routing.channel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexusai.billing.crypto.CredentialCipher;
import com.nexusai.control.domain.routing.channel.vo.ChannelTestResultVo;
import com.nexusai.persistence.mapper.NaiChannelKeyMapper;
import com.nexusai.persistence.mapper.NaiChannelMapper;
import com.nexusai.persistence.po.NaiChannel;
import com.nexusai.persistence.po.NaiChannelKey;
import io.netty.channel.ChannelOption;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * 渠道测速/探活（缺口分析 G3）：取渠道 baseUrl + 一个启用 Key（解密凭证）→ GET {baseUrl}/models
 * 探测上游连通性 + 鉴权 + 延迟。返回 {ok,httpStatus,latencyMs,message}。<b>仅运维探活·不计费不入账</b>。
 *
 * @author nexusai
 */
@Service
public class ChannelTestService {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(10);
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final int HTTP_OK_MIN = 200;
    private static final int HTTP_OK_MAX_EXCLUSIVE = 300;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;

    private final NaiChannelMapper channelMapper;
    private final NaiChannelKeyMapper keyMapper;
    private final CredentialCipher cipher;
    private final WebClient probeClient;

    public ChannelTestService(NaiChannelMapper channelMapper, NaiChannelKeyMapper keyMapper,
                              CredentialCipher cipher) {
        this.channelMapper = channelMapper;
        this.keyMapper = keyMapper;
        this.cipher = cipher;
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(RESPONSE_TIMEOUT);
        this.probeClient = WebClient.builder().clientConnector(new ReactorClientHttpConnector(http)).build();
    }

    /** 探测一个渠道（阻塞·由控制器经 boundedElastic 包裹；低频运维操作）。 */
    public ChannelTestResultVo test(Long channelId) {
        NaiChannel ch = channelMapper.selectById(channelId);
        if (ch == null) {
            return ChannelTestResultVo.fail("渠道不存在");
        }
        if (!StringUtils.hasText(ch.getBaseUrl())) {
            return ChannelTestResultVo.fail("渠道未配置 baseUrl");
        }
        NaiChannelKey key = keyMapper.selectOne(new LambdaQueryWrapper<NaiChannelKey>()
                .eq(NaiChannelKey::getChannelId, channelId)
                .eq(NaiChannelKey::getStatus, 1)
                .last("limit 1"));
        if (key == null) {
            return ChannelTestResultVo.fail("渠道内无启用 Key 可用于探测");
        }
        String plain;
        try {
            plain = cipher.decrypt(key.getCredential());   // 统一经 CredentialCipher（版本化轮转·不旁路）
        } catch (Exception e) {
            return ChannelTestResultVo.fail("凭证解密失败（密钥不匹配？）");
        }
        String url = ch.getBaseUrl().replaceAll("/+$", "") + "/models";
        long t0 = System.nanoTime();
        try {
            ClientResponse resp = probeClient.get().uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + plain)
                    .exchangeToMono(Mono::just)
                    .block(BLOCK_TIMEOUT);
            long ms = (System.nanoTime() - t0) / NANOS_PER_MILLI;
            int sc = resp == null ? 0 : resp.statusCode().value();
            if (resp != null) {
                resp.releaseBody().subscribe();
            }
            boolean ok = sc >= HTTP_OK_MIN && sc < HTTP_OK_MAX_EXCLUSIVE;
            String message = ok ? "连通正常"
                    : sc == HTTP_UNAUTHORIZED || sc == HTTP_FORBIDDEN ? "可达但鉴权失败（Key 无效？）"
                    : "可达但返回 " + sc;
            return new ChannelTestResultVo(ok, sc, ms, key.getId(), message);
        } catch (Exception e) {
            long ms = (System.nanoTime() - t0) / NANOS_PER_MILLI;
            return new ChannelTestResultVo(false, 0, ms, null, "不可达/超时：" + e.getClass().getSimpleName());
        }
    }
}
