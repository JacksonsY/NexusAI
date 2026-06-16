package com.nexusai.control.domain.project.workspace.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nexusai.billing.quota.QuotaLedger;
import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.common.model.PageQuery;
import com.nexusai.common.model.PageResult;
import com.nexusai.common.model.R;
import com.nexusai.persistence.mapper.NaiWorkspaceMapper;
import com.nexusai.persistence.po.NaiWorkspace;
import lombok.RequiredArgsConstructor;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 项目内工作区管理（/admin/workspaces·三层身份链 B2C 终端用户）。Upsert 幂等键 {@code (projectCode, externalId)}；
 * 钱包额度<b>不在本接口直改</b>——统一走额度操作端点 {@code /admin/quota/op}（带
 * externalWorkspaceId·防双写竞态）；停用/改 unlimited 后失效 Redis 钱包令重播种（闸门 Lua 不查 status，
 * 已播种键必须显式失效才能让停用即时生效）。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/workspaces")
@RequiredArgsConstructor
public class WorkspaceAdminController {

    private final NaiWorkspaceMapper workspaceMapper;
    private final QuotaLedger quotaLedger;

    @GetMapping
    public Mono<R<PageResult<NaiWorkspace>>> list(@RequestParam(defaultValue = "1") long pageNum,
                                             @RequestParam(defaultValue = "10") long pageSize,
                                             @RequestParam(required = false) String projectCode,
                                             @RequestParam(required = false) String keyword,
                                             ServerWebExchange exchange) {
        PageQuery pq = PageQuery.of(pageNum, pageSize);
        // 项目作用域令牌：强制只列自己项目的工作区（忽略传入 projectCode·防越权列举他项目）
        Object restrict = exchange.getAttribute(GatewayConstants.CALLER_PROJECT_ATTR);
        final String scopedProject = restrict != null ? restrict.toString() : projectCode;
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<NaiWorkspace> qw = new LambdaQueryWrapper<NaiWorkspace>()
                    .eq(scopedProject != null && !scopedProject.isBlank(), NaiWorkspace::getProjectCode, scopedProject)
                    .orderByDesc(NaiWorkspace::getId);
            if (keyword != null && !keyword.isBlank()) {
                qw.and(w -> w.like(NaiWorkspace::getExternalId, keyword).or().like(NaiWorkspace::getDisplayName, keyword));
            }
            Page<NaiWorkspace> page = workspaceMapper.selectPage(Page.of(pq.getPageNum(), pq.getPageSize()), qw);
            return R.ok(PageResult.of(page.getRecords(), page.getTotal(), pq.getPageNum(), pq.getPageSize()));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Upsert（幂等键 projectCode+externalId）：存在则更新 displayName/unlimited/status，不存在则创建。
     * 返回完整 NaiWorkspace（业务面拿网关 workspaceId 做映射）。钱包初值仅创建时生效（quotaUsd 可空=0）。
     */
    @PostMapping
    public Mono<R<NaiWorkspace>> upsert(@RequestBody NaiWorkspace body, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            if (body.getProjectCode() == null || body.getProjectCode().isBlank()
                    || body.getExternalId() == null || body.getExternalId().isBlank()) {
                throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "projectCode 与 externalId 必填");
            }
            assertProjectScope(exchange, body.getProjectCode());
            NaiWorkspace existing = workspaceMapper.selectOne(new LambdaQueryWrapper<NaiWorkspace>()
                    .eq(NaiWorkspace::getProjectCode, body.getProjectCode())
                    .eq(NaiWorkspace::getExternalId, body.getExternalId()));
            if (existing == null) {
                body.setId(null);
                if (body.getQuotaUsd() == null) {
                    body.setQuotaUsd(BigDecimal.ZERO);
                }
                if (body.getStatus() == null) {
                    body.setStatus(1);
                }
                workspaceMapper.insert(body);
                return R.ok(body);
            }
            existing.setDisplayName(body.getDisplayName() == null ? existing.getDisplayName() : body.getDisplayName());
            boolean gateAffecting = (body.getUnlimited() != null && !body.getUnlimited().equals(existing.getUnlimited()))
                    || (body.getStatus() != null && !body.getStatus().equals(existing.getStatus()));
            if (body.getUnlimited() != null) {
                existing.setUnlimited(body.getUnlimited());
            }
            if (body.getStatus() != null) {
                existing.setStatus(body.getStatus());
            }
            workspaceMapper.updateById(existing);
            if (gateAffecting) {
                // unlimited/status 变化影响闸门：失效钱包键令下次请求按新状态重播种（停用即时生效）
                quotaLedger.invalidateWorkspace(existing.getProjectCode(), existing.getId());
            }
            return R.ok(existing);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 停用/启用（停用后失效钱包键·该工作区全部 Key 下一请求即被拒）。 */
    @PutMapping("/{id}/status")
    public Mono<R<Void>> status(@PathVariable Long id, @RequestParam Integer status, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            NaiWorkspace workspace = workspaceMapper.selectById(id);
            if (workspace == null) {
                throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "工作区不存在: " + id);
            }
            assertProjectScope(exchange, workspace.getProjectCode());
            workspace.setStatus(status == null || status != 1 ? 0 : 1);
            workspaceMapper.updateById(workspace);
            quotaLedger.invalidateWorkspace(workspace.getProjectCode(), workspace.getId());
            return R.<Void>ok();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 项目作用域令牌跨项目越权校验：受限调用方(CallerScopeWebFilter 写入)的 projectCode 必须 == 目标，否则 403。 */
    private static void assertProjectScope(ServerWebExchange exchange, String targetProjectCode) {
        Object restrict = exchange.getAttribute(GatewayConstants.CALLER_PROJECT_ATTR);
        if (restrict != null && !restrict.equals(targetProjectCode)) {
            throw GatewayException.of(GatewayErrorCode.PROJECT_SCOPE_DENIED,
                    "项目令牌(" + restrict + ")无权操作其他项目: " + targetProjectCode);
        }
    }
}
