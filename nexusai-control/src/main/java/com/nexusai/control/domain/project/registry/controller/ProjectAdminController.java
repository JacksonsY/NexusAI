package com.nexusai.control.domain.project.registry.controller;

import com.nexusai.billing.quota.QuotaLedger;
import com.nexusai.common.model.R;
import com.nexusai.control.api.security.ProjectScopes;
import com.nexusai.control.api.security.ProjectTokenCache;
import com.nexusai.control.api.security.SaTokenConfigure;
import com.nexusai.persistence.mapper.NaiProjectMapper;
import com.nexusai.persistence.po.NaiProject;
import lombok.RequiredArgsConstructor;
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

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/**
 * 项目运营 CRUD（控制面集成边界·/admin/projects·受 {@link SaTokenConfigure} 保护）。
 * <p>项目即工作区 + M2M 接入凭证（{@code project_code + token}），替代单静态 admin token。控制台账密登录（Sa-Token）
 * 自举创建第一个项目；之后接入方用项目 token 调控制面端点。增删改后刷新 {@link ProjectTokenCache}（鉴权热路径即时生效）。
 * <p>令牌展示：列表只回末 4 位（{@code ****abcd}·防列表页裸奔）；明文经 {@code GET /{id}/token} 按需揭示
 * （产品决策：单超管运维台令牌可随时查看），创建/轮转响应亦直接含明文。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/projects")
@RequiredArgsConstructor
public class ProjectAdminController {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final NaiProjectMapper projectMapper;
    private final ProjectTokenCache tokenCache;
    private final QuotaLedger quotaLedger;

    @GetMapping
    public Mono<R<List<NaiProject>>> list() {
        return Mono.fromCallable(() -> {
            List<NaiProject> all = projectMapper.selectList(null);
            all.forEach(p -> p.setToken(mask(p.getToken())));   // 脱敏：列表不回明文
            return R.ok(all);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 新建项目：未带 token 则服务端生成；返回含一次性明文 token（前端自存·不再可查全文）。 */
    @PostMapping
    public Mono<R<NaiProject>> create(@RequestBody NaiProject project) {
        return Mono.fromCallable(() -> {
            project.setId(null);
            if (!StringUtils.hasText(project.getToken())) {
                project.setToken(generateToken());
            }
            if (project.getStatus() == null) {
                project.setStatus(1);
            }
            if (!StringUtils.hasText(project.getScope())) {
                project.setScope(ProjectScopes.PROJECT);   // 默认仅租户自助·平台令牌需显式置 platform
            }
            projectMapper.insert(project);
            tokenCache.broadcastReload();
            return R.ok(project);   // 含明文 token（唯一一次）
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 改项目（token 为空则不动原 token；非空则覆盖）。 */
    @PutMapping("/{id}")
    public Mono<R<Void>> update(@PathVariable Long id, @RequestBody NaiProject project) {
        return Mono.fromCallable(() -> {
            project.setId(id);
            projectMapper.updateById(project);   // MyBatis-Plus 默认不更新 null 字段 → token 空则保留
            tokenCache.broadcastReload();
            // 🔴 停用/归档项目须失效其运行时余额 key：否则已播种的 noeviction 余额 key 仍被 freeze 持续扣减，
            // 流量不会因停用而中断（freeze 仅在 key 缺失时经 seedProject 复查 status）。失效后下次请求 seed 即拒。
            if (project.getStatus() != null && project.getStatus() != 1) {
                NaiProject cur = projectMapper.selectById(id);
                if (cur != null && cur.getProjectCode() != null) {
                    quotaLedger.invalidateProject(cur.getProjectCode());
                }
            }
            return R.<Void>ok();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 查看明文 token（产品决策：运维台单超管语义下令牌可随时查看）。 */
    @GetMapping("/{id}/token")
    public Mono<R<String>> revealToken(@PathVariable Long id) {
        return Mono.fromCallable(() -> {
            NaiProject p = projectMapper.selectById(id);
            return p == null ? R.<String>fail("项目不存在") : R.ok(p.getToken());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 轮转 token：生成新 token，旧 token 立即失效（缓存刷新）；返回一次性明文。 */
    @PostMapping("/{id}/rotate-token")
    public Mono<R<String>> rotateToken(@PathVariable Long id) {
        return Mono.fromCallable(() -> {
            NaiProject p = projectMapper.selectById(id);
            if (p == null) {
                return R.<String>fail("项目不存在");
            }
            String token = generateToken();
            NaiProject upd = new NaiProject();
            upd.setId(id);
            upd.setToken(token);
            projectMapper.updateById(upd);
            tokenCache.broadcastReload();
            return R.ok(token);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{id}")
    public Mono<R<Void>> delete(@PathVariable Long id) {
        return Mono.fromCallable(() -> {
            NaiProject cur = projectMapper.selectById(id);
            projectMapper.deleteById(id);
            tokenCache.broadcastReload();
            if (cur != null && cur.getProjectCode() != null) {
                quotaLedger.invalidateProject(cur.getProjectCode());   // 清运行时余额 key·避免删项目后余额遗留
            }
            return R.<Void>ok();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static String generateToken() {
        byte[] buf = new byte[24];
        RANDOM.nextBytes(buf);
        return "nai_proj_" + Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String mask(String token) {
        if (token == null || token.length() <= 4) {
            return "****";
        }
        return "****" + token.substring(token.length() - 4);
    }
}
