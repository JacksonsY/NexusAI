package com.nexusai.control.api.security;

import com.nexusai.common.util.ClientIp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 可信代理配置注入：启动期把 {@code nexusai.security.trusted-proxies}（逗号分隔 CIDR/IP）灌入
 * {@link ClientIp}。未配置 → 全局忽略 XFF（只信直连对端·防 IP 白名单/黑名单被伪造头绕过）。
 *
 * @author nexusai
 */
@Slf4j
@Component
public class TrustedProxyConfig {

    public TrustedProxyConfig(@Value("${nexusai.security.trusted-proxies:}") String csv) {
        List<String> list = csv == null ? List.of()
                : Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        ClientIp.setTrustedProxies(list);
        if (list.isEmpty()) {
            log.info("可信代理未配置：一律以直连对端为客户端 IP，忽略 X-Forwarded-For（部署在反代后请配置 nexusai.security.trusted-proxies）");
        } else {
            log.info("可信代理已配置 {} 条，将从 X-Forwarded-For 解析真实客户端 IP", list.size());
        }
    }
}
