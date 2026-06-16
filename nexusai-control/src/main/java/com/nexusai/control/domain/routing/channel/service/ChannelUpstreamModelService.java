package com.nexusai.control.domain.routing.channel.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.billing.crypto.CredentialCipher;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.control.domain.routing.channel.vo.ChannelUpstreamModelsVo;
import com.nexusai.persistence.mapper.NaiAbilityMapper;
import com.nexusai.persistence.mapper.NaiChannelKeyMapper;
import com.nexusai.persistence.mapper.NaiChannelMapper;
import com.nexusai.persistence.po.NaiAbility;
import com.nexusai.persistence.po.NaiChannel;
import com.nexusai.persistence.po.NaiChannelKey;
import io.netty.channel.ChannelOption;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 从上游拉取模型列表：GET {baseUrl}/models（按协议家族带鉴权头），
 * 解析模型 id 集合并与本渠道已注册 ability 对照 → 控制台勾选批量注册。<b>仅运维操作·不计费</b>。
 * <p>注册走 ability upsert（复合键 group/model/channel），与 AbilityAdminController 同口径；
 * 新注册默认 group=default / priority=0 / weight=1 / enabled=true / modality=CHAT。
 *
 * @author nexusai
 */
@Service
public class ChannelUpstreamModelService {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(20);
    private static final String PROTOCOL_OPENAI = "openai";
    private static final String PROTOCOL_ANTHROPIC = "anthropic";
    private static final String PROTOCOL_GEMINI = "gemini";
    private static final String DEFAULT_GROUP = "default";
    private static final String ANTHROPIC_VERSION_HEADER = "anthropic-version";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final NaiChannelMapper channelMapper;
    private final NaiChannelKeyMapper keyMapper;
    private final NaiAbilityMapper abilityMapper;
    private final CredentialCipher cipher;
    private final WebClient client;

    public ChannelUpstreamModelService(NaiChannelMapper channelMapper, NaiChannelKeyMapper keyMapper,
                                       NaiAbilityMapper abilityMapper, CredentialCipher cipher) {
        this.channelMapper = channelMapper;
        this.keyMapper = keyMapper;
        this.abilityMapper = abilityMapper;
        this.cipher = cipher;
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(RESPONSE_TIMEOUT);
        this.client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(http)).build();
    }

    /** 拉取上游模型 id 列表 + 本渠道已注册集合（阻塞·由控制器经 boundedElastic 包裹）。 */
    public ChannelUpstreamModelsVo fetch(Long channelId) {
        NaiChannel ch = channelMapper.selectById(channelId);
        if (ch == null || !StringUtils.hasText(ch.getBaseUrl())) {
            return ChannelUpstreamModelsVo.fail(ch == null ? "渠道不存在" : "渠道未配置 baseUrl");
        }
        NaiChannelKey key = keyMapper.selectOne(Wrappers.lambdaQuery(NaiChannelKey.class)
                .eq(NaiChannelKey::getChannelId, channelId)
                .eq(NaiChannelKey::getStatus, 1)
                .last("limit 1"));
        if (key == null) {
            return ChannelUpstreamModelsVo.fail("渠道内无启用 Key");
        }
        String plain;
        try {
            plain = cipher.decrypt(key.getCredential());
        } catch (Exception e) {
            return ChannelUpstreamModelsVo.fail("凭证解密失败");
        }
        String base = ch.getBaseUrl().replaceAll("/+$", "");
        String protocol = ch.getProtocol() == null ? PROTOCOL_OPENAI : ch.getProtocol();
        try {
            String body = switch (protocol) {
                case PROTOCOL_ANTHROPIC -> client.get().uri(base + "/models")
                        .header("x-api-key", plain)
                        .header(ANTHROPIC_VERSION_HEADER, ANTHROPIC_VERSION)
                        .retrieve().bodyToMono(String.class).block(BLOCK_TIMEOUT);
                case PROTOCOL_GEMINI -> client.get().uri(base + "/models?key=" + plain)
                        .retrieve().bodyToMono(String.class).block(BLOCK_TIMEOUT);
                default -> client.get().uri(base + "/models")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + plain)
                        .retrieve().bodyToMono(String.class).block(BLOCK_TIMEOUT);
            };
            List<String> models = parseModelIds(body, protocol);
            // TreeSet 去重排序后转 List（保持有序输出·与原 Map 序列化字节一致）
            List<String> registered = new ArrayList<>(new TreeSet<>(abilityMapper.selectList(
                            Wrappers.lambdaQuery(NaiAbility.class).eq(NaiAbility::getChannelId, channelId))
                    .stream().map(NaiAbility::getModel).toList()));
            return ChannelUpstreamModelsVo.ok(models, registered);
        } catch (Exception e) {
            return ChannelUpstreamModelsVo.fail("拉取失败：" + e.getClass().getSimpleName()
                    + " " + (e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    /** 批量注册勾选的模型为本渠道 ability（已存在的复合键跳过·返回新建数）。 */
    public int register(Long channelId, List<String> models) {
        NaiChannel ch = channelMapper.selectById(channelId);
        if (ch == null || models == null || models.isEmpty()) {
            return 0;
        }
        int created = 0;
        for (String model : new TreeSet<>(models)) {
            if (!StringUtils.hasText(model)) {
                continue;
            }
            Long exists = abilityMapper.selectCount(Wrappers.lambdaQuery(NaiAbility.class)
                    .eq(NaiAbility::getGroupCode, DEFAULT_GROUP)
                    .eq(NaiAbility::getModel, model)
                    .eq(NaiAbility::getChannelId, channelId));
            if (exists != null && exists > 0) {
                continue;
            }
            NaiAbility a = new NaiAbility();
            a.setGroupCode(DEFAULT_GROUP);
            a.setModel(model);
            a.setChannelId(channelId);
            a.setPriority(0);
            a.setWeight(1);
            a.setEnabled(true);
            a.setModality("CHAT");
            abilityMapper.insert(a);
            created++;
        }
        return created;
    }

    /** 解析模型 id：OpenAI/Anthropic = data[].id；Gemini = models[].name（去 models/ 前缀）。 */
    private static List<String> parseModelIds(String body, String protocol) {
        List<String> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        JsonNode root = JsonUtils.readTree(body);
        JsonNode arr = PROTOCOL_GEMINI.equals(protocol) ? root.path("models") : root.path("data");
        for (JsonNode n : arr) {
            String id = PROTOCOL_GEMINI.equals(protocol)
                    ? n.path("name").asText("").replaceFirst("^models/", "")
                    : n.path("id").asText("");
            if (!id.isBlank()) {
                out.add(id);
            }
        }
        out.sort(String::compareTo);
        return out;
    }
}
