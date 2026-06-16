package com.nexusai.control.domain.platform.adminuser;

import com.nexusai.common.util.PasswordHasher;
import com.nexusai.persistence.mapper.NaiAdminUserMapper;
import com.nexusai.persistence.po.NaiAdminUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 默认超管种子（借鉴 snail-ai/snail-job 的 admin/admin123 默认账号）：账密真理源迁到 {@code nai_admin_user} 后，
 * 首次启动若表为空则播种一个超管，避免空库锁死无法登录。账号/口令可经 {@code nexusai.admin.seed-*} 覆盖（仅播种用·非运行时校验源）。
 *
 * @author nexusai
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminUserSeeder implements ApplicationRunner {

    private final NaiAdminUserMapper adminUserMapper;

    @Value("${nexusai.admin.seed-username:admin}")
    private String seedUsername;

    @Value("${nexusai.admin.seed-password:admin123}")
    private String seedPassword;

    @Override
    public void run(ApplicationArguments args) {
        Long count = adminUserMapper.selectCount(null);
        if (count != null && count > 0) {
            return;
        }
        NaiAdminUser admin = new NaiAdminUser();
        admin.setUsername(seedUsername);
        admin.setPassword(PasswordHasher.hash(seedPassword));
        admin.setRole(NaiAdminUser.ROLE_ADMIN);
        admin.setStatus(1);
        adminUserMapper.insert(admin);
        log.warn("已播种默认超管账号 [{}]（口令为默认值·请登录后立即修改）", seedUsername);
    }
}
