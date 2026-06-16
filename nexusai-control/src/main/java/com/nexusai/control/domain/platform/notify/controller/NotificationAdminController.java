package com.nexusai.control.domain.platform.notify.controller;

import com.nexusai.control.domain.platform.notify.service.NotificationService;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 通知服务 admin（/admin/notifications·重要通知独立页）：
 * 配置读写（总开关/全局通道/邮件收件人/通知项+模板）+ 测试发送（同步返回各通道结果）。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/notifications")
@RequiredArgsConstructor
public class NotificationAdminController {

    private final NotificationService notificationService;
    private final SystemConfigService config;

    @GetMapping("/config")
    public Mono<R<Map<String, Object>>> getConfig() {
        return blocking(() -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("enabled", config.getBool("module.important_notification.enabled", false));
            out.put("emailEnabled", config.getBool(NotificationService.CFG_EMAIL_ENABLED, false));
            out.put("emailRecipients", config.getString(NotificationService.CFG_EMAIL_RECIPIENTS, ""));
            out.put("defaultChannel", config.getString(NotificationService.CFG_DEFAULT_CHANNEL, "all"));
            out.put("items", notificationService.items());
            out.put("readiness", notificationService.channelReadiness());
            out.put("sentCount", notificationService.sentCount());
            out.put("failedCount", notificationService.failedCount());
            return out;
        });
    }

    @PutMapping("/config")
    public Mono<R<Void>> putConfig(@RequestBody Map<String, Object> body) {
        return blocking(() -> {
            if (body.containsKey("enabled")) {
                config.put("module.important_notification.enabled",
                        Boolean.TRUE.equals(body.get("enabled")), "通知服务总开关");
            }
            if (body.containsKey("emailEnabled")) {
                config.put(NotificationService.CFG_EMAIL_ENABLED,
                        Boolean.TRUE.equals(body.get("emailEnabled")), "通知服务邮件推送开关");
            }
            if (body.containsKey("emailRecipients")) {
                config.put(NotificationService.CFG_EMAIL_RECIPIENTS,
                        String.valueOf(body.getOrDefault("emailRecipients", "")), "通知服务管理员收件人");
            }
            if (body.containsKey("defaultChannel")) {
                config.put(NotificationService.CFG_DEFAULT_CHANNEL,
                        String.valueOf(body.getOrDefault("defaultChannel", "all")), "通知服务全局推送服务");
            }
            if (body.get("items") instanceof List<?> items) {
                config.put(NotificationService.CFG_ITEMS, items, "通知服务通知项和模板");
            }
            return null;
        });
    }

    @PostMapping("/test")
    public Mono<R<List<Map<String, Object>>>> test(@RequestBody Map<String, Object> body) {
        return blocking(() -> notificationService.sendTest(
                String.valueOf(body.getOrDefault("itemKey", "proxy_node_status")),
                body.get("channel") == null ? null : String.valueOf(body.get("channel"))));
    }

    private static <T> Mono<R<T>> blocking(Callable<T> call) {
        return Mono.fromCallable(call).map(R::ok).defaultIfEmpty(R.ok()).subscribeOn(Schedulers.boundedElastic());
    }
}
