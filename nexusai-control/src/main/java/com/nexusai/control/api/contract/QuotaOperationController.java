package com.nexusai.control.api.contract;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexusai.contract.quota.BalanceView;
import com.nexusai.contract.quota.QuotaOpRequest;
import com.nexusai.billing.quota.QuotaLedger;
import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.common.model.R;
import com.nexusai.billing.money.UsdAmount;
import com.nexusai.persistence.mapper.NaiProjectMapper;
import com.nexusai.persistence.mapper.NaiWorkspaceMapper;
import com.nexusai.persistence.po.NaiProject;
import com.nexusai.persistence.po.NaiWorkspace;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * 额度操作（QuotaOperation·三层身份链·两种操作粒度）：
 * <ul>
 *   <li><b>项目池</b>（externalWorkspaceId 空）：nai_project.quota_usd——B2B 应用额度/分销商进货；</li>
 *   <li><b>工作区钱包</b>（externalWorkspaceId 非空）：nai_workspace.quota_usd——B2C 散户充值/扣减。</li>
 * </ul>
 * add/subtract 以 <b>Redis 运行时余额</b>为基准调整 DB 基线后失效令重播种（不能以 DB 旧基线 + delta：
 * 消耗只发生在 Redis、从不回写 DB，按旧基线算会把已花掉的额度整体复活）；freeze 直接 Redis 原子预扣（仅项目池）；
 * <b>requestId 幂等去重</b>（M11·业务面自动推送 + 人工重放双写竞态下绝不重复结算）。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/quota")
@RequiredArgsConstructor
public class QuotaOperationController {

    /** 配额操作幂等键前缀与 TTL。 */
    private static final String IDEM_PREFIX = "nai:quota:op:";
    private static final long IDEM_TTL_HOURS = 24;

    /** 额度操作码（充值 / 扣减 / 冻结预扣）。 */
    private static final String OP_ADD = "add";
    private static final String OP_SUBTRACT = "subtract";
    private static final String OP_FREEZE = "freeze";

    private final QuotaLedger quotaLedger;
    private final NaiProjectMapper projectMapper;
    private final NaiWorkspaceMapper workspaceMapper;
    private final RedissonClient redisson;

    @PostMapping("/op")
    public Mono<R<BigDecimal>> op(@RequestBody QuotaOpRequest dto, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            if (dto.projectCode() == null || dto.projectCode().isBlank()) {
                throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "projectCode 必填");
            }
            assertProjectScope(exchange, dto.projectCode());
            boolean workspaceScope = dto.externalWorkspaceId() != null && !dto.externalWorkspaceId().isBlank();
            String op = dto.op() == null ? "" : dto.op();
            // 🔴 校验类错误全部前置于幂等预留之前：失败时无预留键残留，不毒化重试（评审 HIGH）。
            if (!OP_ADD.equals(op) && !OP_SUBTRACT.equals(op) && !OP_FREEZE.equals(op)) {
                throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "未知 op: " + dto.op());
            }
            // 🔴 资金安全：金额必须为正。否则负值经 toMicros 翻转操作符号——subtract 负数 = 凭空充值（白嫖）。
            if (dto.usd() == null || dto.usd().signum() <= 0) {
                throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "usd 必须为正数: " + dto.usd());
            }
            if (OP_FREEZE.equals(op) && workspaceScope) {
                throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "freeze 仅支持项目池粒度");
            }
            // 🔴 越权防护：项目作用域令牌（受限调用方）不得充值/预扣自己的项目额度池（= 分销商进货额度·仅平台运营可调）。
            // 仅允许其对工作区钱包充值（给终端用户充值·B2C 自助）与 subtract 扣减。
            boolean restricted = exchange.getAttribute(GatewayConstants.CALLER_PROJECT_ATTR) != null;
            if (restricted && !workspaceScope && (OP_ADD.equals(op) || OP_FREEZE.equals(op))) {
                throw GatewayException.of(GatewayErrorCode.PROJECT_SCOPE_DENIED,
                        "项目令牌不可充值/预扣项目额度池（进货额度仅平台运营可调整）");
            }
            // 🔴 额度操作幂等（资金安全·预留→确认）：requestId SETNX 预留——同一操作重投/双写绝不重复结算配额。
            RBucket<String> guard = (dto.requestId() != null && !dto.requestId().isBlank())
                    ? redisson.getBucket(IDEM_PREFIX + dto.requestId()) : null;
            if (guard != null) {
                String scope = op + ":" + dto.projectCode() + (workspaceScope ? ":" + dto.externalWorkspaceId() : "");
                if (!guard.setIfAbsent(scope, Duration.ofHours(IDEM_TTL_HOURS))) {
                    return R.ok(currentBalance(dto, workspaceScope));   // 已处理（或在途）：幂等返回当前余额
                }
            }
            // 🔴 预留→确认：操作失败必须删除预留键，否则失败的操作把 24h 内的合法重试毒化成「返回旧余额却从未执行」
            // （资金操作静默丢失·评审 HIGH）。校验类错误已全部前置于预留之前，此处失败仅为 Redis/DB 变更异常。
            long deltaMicro = UsdAmount.toMicros(dto.usd());
            try {
                switch (op) {
                    case OP_ADD -> adjust(dto, workspaceScope, deltaMicro);
                    case OP_SUBTRACT -> adjust(dto, workspaceScope, -deltaMicro);
                    case OP_FREEZE -> quotaLedger.freezeProject(dto.projectCode(), deltaMicro);
                    default -> throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "未知 op: " + dto.op());
                }
            } catch (RuntimeException e) {
                if (guard != null) {
                    guard.delete();   // 释放幂等预留，使合法重试可重新执行
                }
                throw e;
            }
            return R.ok(currentBalance(dto, workspaceScope));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 实时余额：externalWorkspaceId 空=项目池（unlimited 项目回 {@code unlimited:true}）；非空=该工作区钱包。 */
    @GetMapping("/{projectCode}/balance")
    public Mono<R<BalanceView>> balance(@PathVariable String projectCode,
                                        @RequestParam(required = false) String externalWorkspaceId,
                                        ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            assertProjectScope(exchange, projectCode);
            if (externalWorkspaceId != null && !externalWorkspaceId.isBlank()) {
                NaiWorkspace user = requireUser(projectCode, externalWorkspaceId);
                boolean unlimited = Boolean.TRUE.equals(user.getUnlimited());
                return R.ok(BalanceView.ofWorkspace(projectCode, externalWorkspaceId, user.getId(), unlimited,
                        unlimited ? null : UsdAmount.format(quotaLedger.workspaceBalance(projectCode, user.getId()))));
            }
            boolean unlimited = quotaLedger.isUnlimited(projectCode);
            return R.ok(BalanceView.ofProject(projectCode, unlimited,
                    unlimited ? null : UsdAmount.format(quotaLedger.projectBalance(projectCode))));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private BigDecimal currentBalance(QuotaOpRequest dto, boolean workspaceScope) {
        if (workspaceScope) {
            NaiWorkspace user = requireUser(dto.projectCode(), dto.externalWorkspaceId());
            return UsdAmount.fromMicros(quotaLedger.workspaceBalance(dto.projectCode(), user.getId()));
        }
        return UsdAmount.fromMicros(quotaLedger.projectBalance(dto.projectCode()));
    }

    /**
     * 充/退（项目池或工作区钱包·deltaMicro 为 micro-USD）：原子 INCRBY Redis 运行时余额（key 缺按 DB 基线先播种），
     * 再把新余额同步回 DB 基线快照。<b>不再 invalidate</b>——原读 Redis→写 DB→删 Redis→重播种存在 TOCTOU：
     * 并发 settle 落在读与失效之间会被陈旧基线覆盖、复活已花额度。原子 INCRBY 与 settle 同源、不丢并发结算。
     */
    private void adjust(QuotaOpRequest dto, boolean workspaceScope, long deltaMicro) {
        if (workspaceScope) {
            NaiWorkspace user = requireUser(dto.projectCode(), dto.externalWorkspaceId());
            if (Boolean.TRUE.equals(user.getUnlimited())) {
                throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "不限额用户无需调整钱包: " + dto.externalWorkspaceId());
            }
            long baseline = UsdAmount.toMicros(user.getQuotaUsd());
            long newBalance = quotaLedger.adjustWorkspace(dto.projectCode(), user.getId(), baseline, deltaMicro);
            user.setQuotaUsd(UsdAmount.fromMicros(newBalance));
            workspaceMapper.updateById(user);
            return;
        }
        NaiProject project = projectMapper.selectOne(new LambdaQueryWrapper<NaiProject>()
                .eq(NaiProject::getProjectCode, dto.projectCode()));
        if (project == null) {
            throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "项目不存在: " + dto.projectCode());
        }
        if (Boolean.TRUE.equals(project.getUnlimited())) {
            throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "不限额项目无需调整额度: " + dto.projectCode());
        }
        long baseline = UsdAmount.toMicros(project.getQuotaUsd());
        long newBalance = quotaLedger.adjustProject(dto.projectCode(), baseline, deltaMicro);
        project.setQuotaUsd(UsdAmount.fromMicros(newBalance));
        projectMapper.updateById(project);
    }

    private NaiWorkspace requireUser(String projectCode, String externalWorkspaceId) {
        NaiWorkspace user = workspaceMapper.selectOne(new LambdaQueryWrapper<NaiWorkspace>()
                .eq(NaiWorkspace::getProjectCode, projectCode).eq(NaiWorkspace::getExternalId, externalWorkspaceId));
        if (user == null) {
            throw GatewayException.of(GatewayErrorCode.BAD_REQUEST,
                    "项目内工作区不存在: " + projectCode + "/" + externalWorkspaceId + "（请先经契约①开 Key 或 /admin/workspaces 创建）");
        }
        return user;
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
