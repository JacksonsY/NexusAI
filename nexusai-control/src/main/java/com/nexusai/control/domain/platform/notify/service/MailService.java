package com.nexusai.control.domain.platform.notify.service;

import com.nexusai.billing.crypto.CredentialCipher;
import com.nexusai.control.domain.platform.system.service.SystemConfigService;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.Properties;
import java.util.concurrent.atomic.LongAdder;

/**
 * SMTP 邮件服务（仅运维告警通道，不做注册验证码——登录业务不在网关）。
 * 配置存 KV（smtp_host/port/user/password(AES)/from_email/from_name/encryption），
 * 每次发送按当前配置构建 sender——配置热生效、无需重启。
 *
 * @author nexusai
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    public static final String CFG_HOST = "smtp_host";
    public static final String CFG_PORT = "smtp_port";
    public static final String CFG_USER = "smtp_user";
    public static final String CFG_PASSWORD = "smtp_password";
    public static final String CFG_FROM_EMAIL = "smtp_from_email";
    public static final String CFG_FROM_NAME = "smtp_from_name";
    public static final String CFG_ENCRYPTION = "smtp_encryption";

    /** SMTP 默认端口（STARTTLS submission） */
    private static final int DEFAULT_SMTP_PORT = 587;
    /** SMTP 连接/读写超时（ms·防卡死事件外线程） */
    private static final String SMTP_TIMEOUT_MS = "10000";

    private final SystemConfigService config;
    private final CredentialCipher cipher;

    private final LongAdder sentCount = new LongAdder();
    private final LongAdder failedCount = new LongAdder();

    /** SMTP 是否已配置（host + 发件人邮箱齐备）。 */
    public boolean configured() {
        return !config.getString(CFG_HOST, "").isBlank()
                && !config.getString(CFG_FROM_EMAIL, "").isBlank();
    }

    /** 测试 SMTP 连接（同步·boundedElastic 调用）。 */
    public void testConnection() {
        JavaMailSenderImpl sender = buildSender();
        try {
            sender.testConnection();
        } catch (Exception e) {
            throw new IllegalStateException("SMTP 连接失败: " + e.getMessage(), e);
        }
    }

    /** 同步发送（测试通道/通知服务调用方自行选择线程）。失败抛异常。 */
    public void send(String to, String subject, String text) {
        JavaMailSenderImpl sender = buildSender();
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            String fromEmail = config.getString(CFG_FROM_EMAIL, "");
            String fromName = config.getString(CFG_FROM_NAME, "NexusAI");
            helper.setFrom(fromEmail, fromName);
            helper.setTo(to.trim());
            helper.setSubject(subject);
            helper.setText(text, false);
            sender.send(message);
            sentCount.increment();
        } catch (Exception e) {
            failedCount.increment();
            throw new IllegalStateException("邮件发送失败: " + e.getMessage(), e);
        }
    }

    public long sentCount() {
        return sentCount.sum();
    }

    public long failedCount() {
        return failedCount.sum();
    }

    private JavaMailSenderImpl buildSender() {
        String host = config.getString(CFG_HOST, "");
        if (host.isBlank()) {
            throw new IllegalStateException("SMTP 未配置（缺少服务器地址）");
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(config.getInt(CFG_PORT, DEFAULT_SMTP_PORT));
        String user = config.getString(CFG_USER, "");
        if (!user.isBlank()) {
            sender.setUsername(user);
            sender.setPassword(decryptPassword());
        }
        sender.setDefaultEncoding("UTF-8");
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.connectiontimeout", SMTP_TIMEOUT_MS);
        props.put("mail.smtp.timeout", SMTP_TIMEOUT_MS);
        props.put("mail.smtp.writetimeout", SMTP_TIMEOUT_MS);
        String encryption = config.getString(CFG_ENCRYPTION, "tls");
        if ("ssl".equals(encryption)) {
            props.put("mail.smtp.ssl.enable", "true");
        } else if ("tls".equals(encryption)) {
            props.put("mail.smtp.starttls.enable", "true");
        }
        if (!user.isBlank()) {
            props.put("mail.smtp.auth", "true");
        }
        return sender;
    }

    private String decryptPassword() {
        String enc = config.getString(CFG_PASSWORD, "");
        if (enc.isBlank()) {
            return "";
        }
        try {
            return cipher.decrypt(enc);
        } catch (Exception e) {
            // 解密失败不能静默回退空密码（会用空口令连 SMTP 致误导性认证失败）；显式 fail-fast 暴露凭证配置问题
            throw new IllegalStateException("SMTP 密码解密失败（请重新配置 smtp_password）: " + e.getMessage(), e);
        }
    }
}
