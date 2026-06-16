package com.nexusai.control.domain.governance.ipsec.controller;

import com.nexusai.common.util.IpMatcher;
import com.nexusai.control.domain.governance.ipsec.service.IpSecurityService;
import com.nexusai.control.domain.governance.ipsec.vo.IpBlacklistEntryVo;
import com.nexusai.common.model.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * IP 安全 admin（/admin/ip-security）：黑名单（Redis TTL）/白名单（KV CIDR）。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/ip-security")
@RequiredArgsConstructor
public class IpSecurityAdminController {

    private final IpSecurityService ipSecurityService;

    @GetMapping("/blacklist")
    public Mono<R<List<IpBlacklistEntryVo>>> blacklist() {
        return blocking(ipSecurityService::listBlacklist);
    }

    @PostMapping("/blacklist")
    public Mono<R<Void>> addBlacklist(@RequestBody Map<String, Object> body) {
        return blocking(() -> {
            String ip = String.valueOf(body.get("ip")).trim();
            if (!IpMatcher.isValidEntry(ip) || ip.contains("/")) {
                throw new IllegalArgumentException("无效的 IP 地址: " + ip);
            }
            Long ttl = body.get("ttlSeconds") instanceof Number n ? n.longValue() : null;
            ipSecurityService.addBlacklist(ip, (String) body.get("reason"), ttl);
            return null;
        });
    }

    @DeleteMapping("/blacklist")
    public Mono<R<Void>> removeBlacklist(@RequestParam String ip) {
        return blocking(() -> {
            ipSecurityService.removeBlacklist(ip);
            return null;
        });
    }

    @GetMapping("/whitelist")
    public Mono<R<List<String>>> whitelist() {
        return blocking(ipSecurityService::whitelist);
    }

    @PostMapping("/whitelist")
    public Mono<R<Void>> addWhitelist(@RequestBody Map<String, Object> body) {
        return blocking(() -> {
            ipSecurityService.addWhitelist(String.valueOf(body.get("ip")));
            return null;
        });
    }

    @DeleteMapping("/whitelist")
    public Mono<R<Void>> removeWhitelist(@RequestParam String ip) {
        return blocking(() -> {
            ipSecurityService.removeWhitelist(ip);
            return null;
        });
    }

    private static <T> Mono<R<T>> blocking(Callable<T> call) {
        return Mono.fromCallable(call).map(R::ok).defaultIfEmpty(R.ok()).subscribeOn(Schedulers.boundedElastic());
    }
}
