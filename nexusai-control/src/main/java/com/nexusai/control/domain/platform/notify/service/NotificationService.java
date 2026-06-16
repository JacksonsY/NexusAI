package com.nexusai.control.domain.platform.notify.service;

import com.nexusai.billing.crypto.CredentialCipher;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.control.domain.platform.system.service.SystemConfigService;
import com.nexusai.runtime.ModuleToggles;
import io.netty.buffer.Unpooled;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 通知服务（重要通知独立页）：通知项驱动——每个通知项可继承全局推送服务
 * 或单独指定通道（email / server_chan / bark / all），支持标题/正文模板（{title}/{body} 占位）。
 * 通道凭证 AES 加密存 KV；常规事件 fire-and-forget，测试发送同步返回各通道结果。
 * 裁剪边界：无终端用户体系——邮件仅发管理员收件人（user_email 概念不做）。
 *
 * @author nexusai
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    public static final String CFG_EMAIL_ENABLED = "module.important_notification.email_enabled";
    public static final String CFG_EMAIL_RECIPIENTS = "module.important_notification.email_recipients";
    public static final String CFG_DEFAULT_CHANNEL = "module.important_notification.default_channel";
    public static final String CFG_ITEMS = "module.important_notification.items";

    /** Server 酱推送基址（{sendKey}.send 拼接）与 Bark 默认服务器 */
    private static final String SERVER_CHAN_BASE_URL = "https://sctapi.ftqq.com/";
    private static final String BARK_DEFAULT_SERVER = "https://api.day.app";
    /** 同步 HTTP 投递超时（响应/阻塞双层·后者略宽以兜底） */
    private static final Duration PUSH_RESPONSE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration PUSH_BLOCK_TIMEOUT = Duration.ofSeconds(12);
    /** HTTP 非成功状态线（含）以上即判失败 */
    private static final int HTTP_ERROR_STATUS = 400;

    /** 内置通知项（首次加载/items 缺失时的缺省集） */
    private static final List<Map<String, Object>> DEFAULT_ITEMS = List.of(
            item("proxy_node_status", "代理节点状态翻转", "{title}", "{body}"),
            item("channel_circuit_open", "渠道熔断告警", "渠道熔断：{title}", "{body}"),
            item("config_backup_failed", "配置备份失败", "配置备份失败", "{body}"),
            item("ops_alert", "运维监控告警", "{title}", "{body}"));

    private static Map<String, Object> item(String key, String name, String titleTpl, String bodyTpl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("name", name);
        m.put("enabled", true);
        m.put("channel", "global");
        m.put("titleTemplate", titleTpl);
        m.put("bodyTemplate", bodyTpl);
        m.put("system", true);
        return m;
    }

    private final ModuleToggles toggles;
    private final SystemConfigService config;
    private final CredentialCipher cipher;
    private final MailService mailService;

    private final LongAdder sentCount = new LongAdder();
    private final LongAdder failedCount = new LongAdder();

    // ---------- 事件入口 ----------

    /** 按通知项发送（模块关闭/项关闭/无可用通道时静默跳过·fire-and-forget）。 */
    public void notify(String itemKey, String title, String body) {
        if (!toggles.enabled("important_notification", false)) {
            return;
        }
        Map<String, Object> item = findItem(itemKey);
        if (item == null || Boolean.FALSE.equals(item.get("enabled"))) {
            return;
        }
        String renderedTitle = render(str(item, "titleTemplate", "{title}"), title, body);
        String renderedBody = render(str(item, "bodyTemplate", "{body}"), title, body);
        for (String channel : resolveChannels(str(item, "channel", "global"))) {
            dispatchAsync(channel, renderedTitle, renderedBody);
        }
    }

    /** 测试发送（同步·返回各通道结果·channelOverride 为空则按通知项配置）。 */
    public List<Map<String, Object>> sendTest(String itemKey, String channelOverride) {
        Map<String, Object> item = findItem(itemKey);
        String channelSpec = channelOverride != null && !channelOverride.isBlank()
                ? channelOverride
                : (item == null ? "global" : str(item, "channel", "global"));
        String title = render(item == null ? "{title}" : str(item, "titleTemplate", "{title}"),
                "NexusAI 测试通知", "通知服务连通性测试");
        String body = render(item == null ? "{body}" : str(item, "bodyTemplate", "{body}"),
                "NexusAI 测试通知", "通知服务连通性测试（来自通知服务·发送测试）");
        List<String> channels = resolveChannels(channelSpec);
        List<Map<String, Object>> results = new ArrayList<>();
        if (channels.isEmpty()) {
            results.add(Map.of("channel", "none", "success", false, "message", "无可用推送服务"));
            return results;
        }
        for (String channel : channels) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("channel", channel);
            try {
                dispatchSync(channel, title, body);
                r.put("success", true);
                r.put("message", "发送成功");
            } catch (Exception e) {
                r.put("success", false);
                r.put("message", e.getMessage());
            }
            results.add(r);
        }
        return results;
    }

    // ---------- 通知项配置 ----------

    /** 通知项列表（items 未配置时回落内置缺省集）。 */
    public List<Map<String, Object>> items() {
        Object v = config.getParsed(CFG_ITEMS);
        if (v instanceof List<?> list && !list.isEmpty()) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    m.forEach((k, val) -> copy.put(String.valueOf(k), val));
                    out.add(copy);
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        return DEFAULT_ITEMS.stream().map(LinkedHashMap::new).map(m -> (Map<String, Object>) m).toList();
    }

    private Map<String, Object> findItem(String key) {
        return items().stream().filter(i -> key.equals(i.get("key"))).findFirst().orElse(null);
    }

    /** 通道就绪状态（前端独立页展示）。 */
    public Map<String, Object> channelReadiness() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("emailReady", emailReady());
        out.put("smtpConfigured", mailService.configured());
        out.put("serverChanReady", serverChanReady());
        out.put("barkReady", barkReady());
        return out;
    }

    // ---------- 通道路由 ----------

    /** 解析通道说明为实际就绪通道列表：global→默认；all→全部就绪通道；单通道→就绪才返回。 */
    private List<String> resolveChannels(String channelSpec) {
        String spec = "global".equals(channelSpec)
                ? config.getString(CFG_DEFAULT_CHANNEL, "all") : channelSpec;
        List<String> out = new ArrayList<>();
        if ("all".equals(spec)) {
            if (emailReady()) {
                out.add("email");
            }
            if (serverChanReady()) {
                out.add("server_chan");
            }
            if (barkReady()) {
                out.add("bark");
            }
            return out;
        }
        boolean ready = switch (spec) {
            case "email" -> emailReady();
            case "server_chan" -> serverChanReady();
            case "bark" -> barkReady();
            default -> false;
        };
        if (ready) {
            out.add(spec);
        }
        return out;
    }

    private boolean emailReady() {
        return config.getBool(CFG_EMAIL_ENABLED, false)
                && mailService.configured()
                && !recipients().isEmpty();
    }

    private boolean serverChanReady() {
        return toggles.enabled("server_chan_push", false)
                && secret("module.server_chan_push.send_key") != null;
    }

    private boolean barkReady() {
        return toggles.enabled("bark_push", false)
                && secret("module.bark_push.device_key") != null;
    }

    private List<String> recipients() {
        String raw = config.getString(CFG_EMAIL_RECIPIENTS, "");
        return raw.lines().map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    // ---------- 投递 ----------

    private void dispatchAsync(String channel, String title, String body) {
        Mono.fromRunnable(() -> dispatchSync(channel, title, body))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(v -> {
                }, e -> log.warn("{} 通知投递失败: {}", channel, e.toString()));
    }

    /** 同步投递单通道（失败抛异常·测试路径直接复用）。 */
    private void dispatchSync(String channel, String title, String body) {
        switch (channel) {
            case "email" -> {
                List<String> to = recipients();
                if (to.isEmpty()) {
                    throw new IllegalStateException("未配置管理员收件人");
                }
                for (String addr : to) {
                    mailService.send(addr, title, body);
                }
                sentCount.increment();
            }
            case "server_chan" -> {
                String sendKey = secret("module.server_chan_push.send_key");
                if (sendKey == null) {
                    throw new IllegalStateException("Server酱 SendKey 未配置");
                }
                postSync("ServerChan",
                        SERVER_CHAN_BASE_URL + sendKey + ".send",
                        "title=" + url(title) + "&desp=" + url(body),
                        "application/x-www-form-urlencoded");
            }
            case "bark" -> {
                String deviceKey = secret("module.bark_push.device_key");
                if (deviceKey == null) {
                    throw new IllegalStateException("Bark DeviceKey 未配置");
                }
                String server = config.getString("module.bark_push.server", BARK_DEFAULT_SERVER);
                postSync("Bark",
                        stripSlash(server) + "/push",
                        JsonUtils.toJson(Map.of("device_key", deviceKey, "title", title, "body", body)),
                        "application/json");
            }
            default -> throw new IllegalStateException("未知通道: " + channel);
        }
    }

    public long sentCount() {
        return sentCount.sum();
    }

    public long failedCount() {
        return failedCount.sum();
    }

    private String secret(String key) {
        String enc = config.getString(key, null);
        if (enc == null || enc.isBlank()) {
            return null;
        }
        try {
            return cipher.decrypt(enc);
        } catch (Exception e) {
            log.warn("通知通道凭证解密失败 {}: {}", key, e.toString());
            return null;
        }
    }

    /** 同步 HTTP POST（boundedElastic 线程·12s 上限·非 2xx/异常即失败）。 */
    private void postSync(String channel, String uri, String body, String contentType) {
        try {
            Integer status = HttpClient.create()
                    .headers(h -> h.set("Content-Type", contentType))
                    .responseTimeout(PUSH_RESPONSE_TIMEOUT)
                    .post()
                    .uri(uri)
                    .send(Mono.just(
                            Unpooled.wrappedBuffer(body.getBytes(StandardCharsets.UTF_8))))
                    .responseSingle((resp, bytes) ->
                            bytes.asString().defaultIfEmpty("").map(s -> resp.status().code()))
                    .block(PUSH_BLOCK_TIMEOUT);
            if (status == null || status >= HTTP_ERROR_STATUS) {
                failedCount.increment();
                throw new IllegalStateException(channel + " 推送失败 http=" + status);
            }
            sentCount.increment();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            failedCount.increment();
            throw new IllegalStateException(channel + " 推送异常: " + e.getMessage(), e);
        }
    }

    private static String render(String template, String title, String body) {
        String tpl = template == null || template.isBlank() ? "{title}" : template;
        return tpl.replace("{title}", title == null ? "" : title)
                .replace("{body}", body == null ? "" : body);
    }

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null || String.valueOf(v).isBlank() ? def : String.valueOf(v);
    }

    private static String url(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
