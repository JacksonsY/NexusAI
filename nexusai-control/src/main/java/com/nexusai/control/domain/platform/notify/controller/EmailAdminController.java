package com.nexusai.control.domain.platform.notify.controller;

import com.nexusai.control.domain.platform.notify.service.MailService;
import com.nexusai.billing.crypto.CredentialCipher;
import com.nexusai.common.model.R;
import com.nexusai.control.domain.platform.system.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 邮件配置 admin（/admin/email·仅 SMTP 段，注册邮箱验证为登录业务不做）：
 * 配置读写（密码 AES 加密·回显仅 passwordSet 标记）+ 连接测试 + 发送测试。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/email")
@RequiredArgsConstructor
public class EmailAdminController {

    private final SystemConfigService config;
    private final MailService mailService;
    private final CredentialCipher cipher;

    @GetMapping("/config")
    public Mono<R<Map<String, Object>>> getConfig() {
        return blocking(() -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("host", config.getString(MailService.CFG_HOST, ""));
            out.put("port", config.getInt(MailService.CFG_PORT, 587));
            out.put("user", config.getString(MailService.CFG_USER, ""));
            out.put("passwordSet", !config.getString(MailService.CFG_PASSWORD, "").isBlank());
            out.put("fromEmail", config.getString(MailService.CFG_FROM_EMAIL, ""));
            out.put("fromName", config.getString(MailService.CFG_FROM_NAME, ""));
            out.put("encryption", config.getString(MailService.CFG_ENCRYPTION, "tls"));
            out.put("sentCount", mailService.sentCount());
            out.put("failedCount", mailService.failedCount());
            return out;
        });
    }

    /** 保存配置（password 缺省/空=保持不变·"" 显式置空=清除）。 */
    @PutMapping("/config")
    public Mono<R<Void>> putConfig(@RequestBody Map<String, Object> body) {
        return blocking(() -> {
            putString(body, "host", MailService.CFG_HOST, "SMTP 服务器地址");
            if (body.get("port") instanceof Number n) {
                config.put(MailService.CFG_PORT, n.intValue(), "SMTP 端口");
            }
            putString(body, "user", MailService.CFG_USER, "SMTP 用户名");
            putString(body, "fromEmail", MailService.CFG_FROM_EMAIL, "发件人邮箱");
            putString(body, "fromName", MailService.CFG_FROM_NAME, "发件人名称");
            putString(body, "encryption", MailService.CFG_ENCRYPTION, "SMTP 加密方式");
            if (body.containsKey("password")) {
                String pw = body.get("password") == null ? "" : String.valueOf(body.get("password"));
                if (pw.isBlank()) {
                    config.delete(MailService.CFG_PASSWORD);
                } else {
                    config.put(MailService.CFG_PASSWORD, cipher.encrypt(pw), "SMTP 密码（AES 加密）");
                }
            }
            return null;
        });
    }

    @PostMapping("/test-connection")
    public Mono<R<Map<String, Object>>> testConnection() {
        return blocking(() -> {
            mailService.testConnection();
            return Map.of("message", "SMTP 连接成功");
        });
    }

    @PostMapping("/test-send")
    public Mono<R<Map<String, Object>>> testSend(@RequestBody Map<String, Object> body) {
        return blocking(() -> {
            String to = String.valueOf(body.get("to"));
            mailService.send(to, "NexusAI 测试邮件", "SMTP 邮件通道连通性正常（来自邮件配置·发送测试）。");
            return Map.of("message", "测试邮件已发送至 " + to);
        });
    }

    private void putString(Map<String, Object> body, String field, String cfgKey, String desc) {
        if (body.containsKey(field)) {
            config.put(cfgKey, body.get(field) == null ? "" : String.valueOf(body.get(field)), desc);
        }
    }

    private static <T> Mono<R<T>> blocking(Callable<T> call) {
        return Mono.fromCallable(call).map(R::ok).defaultIfEmpty(R.ok()).subscribeOn(Schedulers.boundedElastic());
    }
}
