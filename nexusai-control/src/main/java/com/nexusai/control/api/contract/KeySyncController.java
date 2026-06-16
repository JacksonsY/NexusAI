package com.nexusai.control.api.contract;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexusai.contract.key.KeySyncRequest;
import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.common.event.ConfigChangedEvent;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.common.model.R;
import com.nexusai.persistence.mapper.NaiTokenMapper;
import com.nexusai.persistence.mapper.NaiWorkspaceMapper;
import com.nexusai.persistence.po.NaiToken;
import com.nexusai.persistence.po.NaiWorkspace;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import com.nexusai.billing.quota.QuotaLedger;

import java.math.BigDecimal;

/**
 * Key 同步（接入方业务面 → 网关·read-only 灌 nai_token 快照）。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/keys")
@RequiredArgsConstructor
public class KeySyncController {

    private final NaiTokenMapper tokenMapper;
    private final NaiWorkspaceMapper workspaceMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final RedissonClient redisson;
    private final QuotaLedger quotaLedger;

    /** 本节点清缓存（进程内事件）+ 跨实例广播（RTopic·评审 H9：吊销即时清各节点·不靠 60s TTL）。 */
    private void broadcastTokenChanged(Long tokenId) {
        eventPublisher.publishEvent(ConfigChangedEvent.token(tokenId));
        redisson.getTopic(GatewayConstants.TOKEN_SYNC_TOPIC, StringCodec.INSTANCE)
                .publish(String.valueOf(tokenId));
    }

    /** 项目作用域令牌跨项目越权校验：受限调用方(CallerScopeWebFilter 写入)的 projectCode 必须 == 目标，否则 403。 */
    private static void assertProjectScope(ServerWebExchange exchange, String targetProjectCode) {
        Object restrict = exchange.getAttribute(GatewayConstants.CALLER_PROJECT_ATTR);
        if (restrict != null && !restrict.equals(targetProjectCode)) {
            throw GatewayException.of(GatewayErrorCode.PROJECT_SCOPE_DENIED,
                    "项目令牌(" + restrict + ")无权操作其他项目: " + targetProjectCode);
        }
    }

    @PutMapping
    public Mono<R<Long>> sync(@RequestBody KeySyncRequest dto, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            assertProjectScope(exchange, dto.projectCode());
            NaiToken token = tokenMapper.selectOne(new LambdaQueryWrapper<NaiToken>()
                    .eq(NaiToken::getKeyHash, dto.keyHash()));
            boolean isNew = token == null;
            if (!isNew && token.getProjectCode() != null) {
                // 🔴 越权防护（与 revoke 同口径）：按 keyHash 命中既有 Key 时，受限令牌还须有权操作该 Key 的既有归属项目，
                // 否则项目 A 可凭已知 keyHash 把项目 B 的 Key 重指/接管到自己项目。平台令牌(restrict=null)放行。
                assertProjectScope(exchange, token.getProjectCode());
            }
            if (isNew) {
                token = new NaiToken();
                token.setKeyHash(dto.keyHash());
            }
            token.setStandalone(0);
            token.setProjectCode(dto.projectCode());
            // 三层身份链：externalWorkspaceId 非空 → 解析/自动建项目内工作区并双挂；空 = B2B 直挂项目池
            token.setWorkspaceId(resolveWorkspaceId(dto.projectCode(), dto.externalWorkspaceId()));
            token.setGroupCode(dto.groupCode());
            token.setModelLimits(dto.modelLimits());
            token.setAllowIps(dto.allowIps());
            token.setQuotaUsd(dto.quotaUsd());   // 项目制：仅 Key 子限额 USD（NULL/≤0=不设）
            token.setRpmLimit(dto.rpmLimit());
            token.setTpmLimit(dto.tpmLimit());
            token.setByokCredential(dto.byokCredential());
            token.setExpiredTime(dto.expiredTime());
            token.setStatus(dto.status() == null ? 1 : dto.status());
            if (isNew) {
                tokenMapper.insert(token);
            } else {
                tokenMapper.updateById(token);
            }
            // 子限额可能变化：失效 Redis 子限额令重播种（项目池不受 Key 同步影响）
            if (token.getProjectCode() != null && !token.getProjectCode().isBlank()) {
                quotaLedger.invalidateToken(token.getProjectCode(), token.getId());
            }
            broadcastTokenChanged(token.getId());
            return R.ok(token.getId());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 解析项目内工作区（externalWorkspaceId → nai_workspace.id）：不存在则自动建（仅建不改·displayName 留空可后补），
     * 并发撞 uk_project_external 唯一键则重查。空 externalWorkspaceId 返回 null（B2B 形态）。
     */
    private Long resolveWorkspaceId(String projectCode, String externalWorkspaceId) {
        if (externalWorkspaceId == null || externalWorkspaceId.isBlank()) {
            return null;
        }
        NaiWorkspace workspace = workspaceMapper.selectOne(new LambdaQueryWrapper<NaiWorkspace>()
                .eq(NaiWorkspace::getProjectCode, projectCode).eq(NaiWorkspace::getExternalId, externalWorkspaceId));
        if (workspace != null) {
            return workspace.getId();
        }
        NaiWorkspace created = new NaiWorkspace();
        created.setProjectCode(projectCode);
        created.setExternalId(externalWorkspaceId);
        created.setQuotaUsd(BigDecimal.ZERO);
        created.setStatus(1);
        try {
            workspaceMapper.insert(created);
            return created.getId();
        } catch (DuplicateKeyException e) {
            NaiWorkspace raced = workspaceMapper.selectOne(new LambdaQueryWrapper<NaiWorkspace>()
                    .eq(NaiWorkspace::getProjectCode, projectCode).eq(NaiWorkspace::getExternalId, externalWorkspaceId));
            return raced == null ? null : raced.getId();
        }
    }

    @PostMapping("/{id}/revoke")
    public Mono<R<Void>> revoke(@PathVariable Long id, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            // 越权防护：先查目标 Key 归属项目，项目作用域令牌只能吊销本项目 Key（与 sync 同闸门·防跨项目 takedown）
            NaiToken existing = tokenMapper.selectById(id);
            if (existing == null) {
                throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "Key 不存在: " + id);
            }
            assertProjectScope(exchange, existing.getProjectCode());
            NaiToken token = new NaiToken();
            token.setId(id);
            token.setStatus(0);
            tokenMapper.updateById(token);
            broadcastTokenChanged(id);
            return R.<Void>ok();
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
