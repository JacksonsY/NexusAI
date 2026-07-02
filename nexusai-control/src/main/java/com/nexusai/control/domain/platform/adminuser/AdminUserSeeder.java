package com.nexusai.control.domain.platform.adminuser;

import com.nexusai.common.util.PasswordHasher;
import com.nexusai.persistence.mapper.NaiAdminUserMapper;
import com.nexusai.persistence.po.NaiAdminUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 默认超管种子（借鉴 snail-ai/snail-job 的 admin/admin123 默认账号）：账密真理源迁到 {@code nai_admin_user} 后，
 * 首次启动若表为空则播种一个超管，避免空库锁死无法登录。账号/口令经 {@code nexusai.admin.username/password}
 * 覆盖（对齐 yml 的 {@code NEXUSAI_ADMIN_USERNAME/PASSWORD} 环境变量·仅播种用·非运行时校验源）。
 * <p>🔴 生产 fail-fast：激活 {@code prod} profile 且口令仍为默认值 {@code admin123} 时拒绝启动，杜绝默认凭证上线。
 *
 * @author nexusai
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminUserSeeder implements ApplicationRunner {

    /** 默认口令（仅本地/dev 允许·生产必须覆盖）。 */
    private static final String DEFAULT_PASSWORD = "admin123";

    private final NaiAdminUserMapper adminUserMapper;
    private final Environment environment;

    @Value("${nexusai.admin.username:admin}")
    private String seedUsername;

    @Value("${nexusai.admin.password:admin123}")
    private String seedPassword;

    @Override
    public void run(ApplicationArguments args) {
        Long count = adminUserMapper.selectCount(null);
        if (count != null && count > 0) {
            return;
        }
        // 🔴 生产环境拒绝默认口令播种（NEXUSAI_ADMIN_PASSWORD 未注入或仍为默认值 → 启动即失败·避免默认凭证上线接管）
        if (DEFAULT_PASSWORD.equals(seedPassword) && isProdProfile()) {
            throw new IllegalStateException(
                    "拒绝以默认口令播种超管：prod 环境必须经 NEXUSAI_ADMIN_PASSWORD 设置强口令（nexusai.admin.password 仍为默认值 admin123）");
        }
        NaiAdminUser admin = new NaiAdminUser();
        admin.setUsername(seedUsername);
        admin.setPassword(PasswordHasher.hash(seedPassword));
        admin.setRole(NaiAdminUser.ROLE_ADMIN);
        admin.setStatus(1);
        adminUserMapper.insert(admin);
        if (DEFAULT_PASSWORD.equals(seedPassword)) {
            log.warn("已播种默认超管账号 [{}]（口令为默认值 admin123·仅限本地/dev·生产务必用 NEXUSAI_ADMIN_PASSWORD 覆盖）", seedUsername);
        } else {
            log.info("已播种超管账号 [{}]", seedUsername);
        }
    }

    /** 是否激活 prod/production profile（用于默认口令 fail-fast·无 profile 视为本地不拦截）。 */
    private boolean isProdProfile() {
        for (String p : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(p) || "production".equalsIgnoreCase(p)) {
                return true;
            }
        }
        return false;
    }
}
