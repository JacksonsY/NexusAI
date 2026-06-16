package com.nexusai.control.domain.platform.adminuser.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.common.model.R;
import com.nexusai.common.util.PasswordHasher;
import com.nexusai.persistence.mapper.NaiAdminUserMapper;
import com.nexusai.persistence.mapper.NaiAdminUserProjectMapper;
import com.nexusai.persistence.po.NaiAdminUser;
import com.nexusai.persistence.po.NaiAdminUserProject;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 控制台运维账号管理（/admin/admin-users·仅超管/平台令牌可达——不在 {@code ProjectScopes.PROJECT_ALLOWED}）。
 * 借鉴 snail-ai 用户管理：增删运维账号、改角色/状态、重置口令、分配项目（项目运维身份的命名空间授权）。
 * 口令仅经 {@link PasswordHasher} 哈希落库·列表/详情永不回明文。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/admin-users")
@RequiredArgsConstructor
public class AdminUserAdminController {

    private final NaiAdminUserMapper adminUserMapper;
    private final NaiAdminUserProjectMapper adminUserProjectMapper;

    /** 账号视图（不含口令哈希）。 */
    public record AdminUserView(Long id, String username, Integer role, Integer status,
                                List<String> projects, LocalDateTime createDt) {
    }

    @GetMapping
    public Mono<R<List<AdminUserView>>> list() {
        return Mono.fromCallable(() -> {
            List<NaiAdminUser> users = adminUserMapper.selectList(
                    new LambdaQueryWrapper<NaiAdminUser>().orderByAsc(NaiAdminUser::getId));
            List<AdminUserView> views = users.stream().map(u -> new AdminUserView(
                    u.getId(), u.getUsername(), u.getRole(), u.getStatus(),
                    grantedProjects(u.getId()), u.getCreateDt())).toList();
            return R.ok(views);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping
    public Mono<R<Long>> create(@RequestBody UpsertReq req) {
        return Mono.fromCallable(() -> {
            if (!StringUtils.hasText(req.getUsername()) || !StringUtils.hasText(req.getPassword())) {
                throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "账号与口令必填");
            }
            NaiAdminUser u = new NaiAdminUser();
            u.setUsername(req.getUsername().trim());
            u.setPassword(PasswordHasher.hash(req.getPassword()));
            u.setRole(req.getRole() == null ? NaiAdminUser.ROLE_USER : req.getRole());
            u.setStatus(1);
            try {
                adminUserMapper.insert(u);
            } catch (DuplicateKeyException e) {
                throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "账号已存在: " + req.getUsername());
            }
            replaceProjects(u.getId(), u.getRole(), req.getProjects());
            return R.ok(u.getId());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 改角色/状态/授权项目（不含口令；口令走 {@code PUT /{id}/password}）。 */
    @PutMapping("/{id}")
    public Mono<R<Void>> update(@PathVariable Long id, @RequestBody UpsertReq req) {
        return Mono.fromCallable(() -> {
            NaiAdminUser u = adminUserMapper.selectById(id);
            if (u == null) {
                throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "账号不存在: " + id);
            }
            if (req.getRole() != null) {
                u.setRole(req.getRole());
            }
            if (req.getStatus() != null) {
                // 不允许停用最后一个启用的超管（防自锁死）
                if (req.getStatus() != 1 && NaiAdminUser.ROLE_ADMIN == u.getRole() && lastActiveAdmin(id)) {
                    throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "不能停用最后一个启用的超管");
                }
                u.setStatus(req.getStatus());
            }
            adminUserMapper.updateById(u);
            replaceProjects(u.getId(), u.getRole(), req.getProjects());
            return R.<Void>ok();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 重置口令。 */
    @PutMapping("/{id}/password")
    public Mono<R<Void>> resetPassword(@PathVariable Long id, @RequestBody PasswordReq req) {
        return Mono.fromCallable(() -> {
            if (!StringUtils.hasText(req.getPassword())) {
                throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "口令必填");
            }
            NaiAdminUser u = adminUserMapper.selectById(id);
            if (u == null) {
                throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "账号不存在: " + id);
            }
            u.setPassword(PasswordHasher.hash(req.getPassword()));
            adminUserMapper.updateById(u);
            return R.<Void>ok();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{id}")
    public Mono<R<Void>> delete(@PathVariable Long id) {
        return Mono.fromCallable(() -> {
            NaiAdminUser u = adminUserMapper.selectById(id);
            if (u == null) {
                return R.<Void>ok();
            }
            if (NaiAdminUser.ROLE_ADMIN == u.getRole() && lastActiveAdmin(id)) {
                throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "不能删除最后一个启用的超管");
            }
            adminUserMapper.deleteById(id);
            adminUserProjectMapper.delete(new LambdaQueryWrapper<NaiAdminUserProject>()
                    .eq(NaiAdminUserProject::getAdminUserId, id));
            return R.<Void>ok();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 重置授权项目集（超管无需登记·清空即可；项目运维全量覆盖）。 */
    private void replaceProjects(Long userId, Integer role, List<String> projects) {
        adminUserProjectMapper.delete(new LambdaQueryWrapper<NaiAdminUserProject>()
                .eq(NaiAdminUserProject::getAdminUserId, userId));
        if (NaiAdminUser.ROLE_USER != role || projects == null) {
            return;
        }
        projects.stream().filter(StringUtils::hasText).distinct().forEach(code -> {
            NaiAdminUserProject g = new NaiAdminUserProject();
            g.setAdminUserId(userId);
            g.setProjectCode(code.trim());
            adminUserProjectMapper.insert(g);
        });
    }

    private List<String> grantedProjects(Long userId) {
        return adminUserProjectMapper.selectList(new LambdaQueryWrapper<NaiAdminUserProject>()
                        .eq(NaiAdminUserProject::getAdminUserId, userId))
                .stream().map(NaiAdminUserProject::getProjectCode).toList();
    }

    /** 排除指定 id 后是否已无其它启用的超管。 */
    private boolean lastActiveAdmin(Long excludeId) {
        Long others = adminUserMapper.selectCount(new LambdaQueryWrapper<NaiAdminUser>()
                .eq(NaiAdminUser::getRole, NaiAdminUser.ROLE_ADMIN)
                .eq(NaiAdminUser::getStatus, 1)
                .ne(NaiAdminUser::getId, excludeId));
        return others == null || others == 0;
    }

    @Data
    public static class UpsertReq {
        private String username;
        private String password;
        private Integer role;
        private Integer status;
        private List<String> projects;
    }

    @Data
    public static class PasswordReq {
        private String password;
    }
}
