package com.nexusai.billing.quota;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.billing.money.UsdAmount;
import com.nexusai.persistence.mapper.NaiProjectMapper;
import com.nexusai.persistence.mapper.NaiTokenMapper;
import com.nexusai.persistence.mapper.NaiWorkspaceMapper;
import com.nexusai.persistence.po.NaiProject;
import com.nexusai.persistence.po.NaiToken;
import com.nexusai.persistence.po.NaiWorkspace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * USD 三级强闸门（三层身份链·强闸门）：
 * <b>项目池</b>（容器总闸·B2C 站点通常 unlimited / 分销商按进货额度设限）→
 * <b>工作区钱包</b>（B2C 主闸·token 挂 workspace 时启用）→ <b>Key 子限额</b>（可选副闸）。
 * 单段 Lua 原子检查+扣减（任一不足整体回滚拒绝，微秒级·非只读软判断），流末原子多退少补。
 * <p>账本一律 <b>micro-USD 整数</b>（{@link UsdAmount}·1 USD=10^6·Redis DECRBY/INCRBY 原子性要求整数·禁 float）；
 * DB 基线 {@code quota_usd} 为 DECIMAL(18,6) USD，seed 时经 {@link UsdAmount#toMicros} 精确折算。
 * <p>两种形态：B2B 套壳应用（token 不挂 workspace）走「项目池→子限额」两级；B2C 中转站（token 挂 workspace）
 * 走三级——同一 Lua 按 ARGV 开关复用。<b>不碰钱、不计价</b>——计价/钱包法币侧在业务面，经业务面充退。
 *
 * <p>Redis key 以项目码做 hash tag（{@code nai:usd:{p:code}} / {@code …:w:workspaceId} / {@code …:t:tokenId}），
 * cluster 下三 key 必同 slot，Lua 原子性成立。
 *
 * <p>🔴 <b>USD 余额 key 必须持久、不可被 Redis 驱逐</b>（架构评审 2026-06-07）：余额是运行时真理源，
 * DB 仅作首次 seed。一旦 key 在 maxmemory 驱逐/failover 中丢失，seed 会用<b>陈旧</b>的 DB 基线重播种，
 * 把"已花掉"的额度复活（少扣/超支）。因此部署上 USD 余额必须落在 <b>noeviction</b> 策略的 Redis
 * （或独立实例/库），且本类对余额 key <b>从不设 TTL</b>；重播种只应发生在冷启动与业务面改额后。
 *
 * @author nexusai
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaLedger {

    /** 无限额度哨兵值（unlimited 项目/用户播种此值·闸门照扣但永远扣不穿） */
    private static final long UNLIMITED = Long.MAX_VALUE / 4;
    /** freeze 重播种重试上限（项目/用户/子限额三级各可能首查未播种） */
    private static final int FREEZE_MAX_ROUNDS = 4;

    /**
     * 三级原子预扣：KEYS[1]=项目池 KEYS[2]=工作区钱包 KEYS[3]=Key 子限额；
     * ARGV[1]=预估 USD（micro-USD 整数），ARGV[2]='1' 检查用户级，ARGV[3]='1' 检查子限额。
     * 先全量校验后统一扣减（任一不足整体不扣）。
     * 返回 -1=项目未播种 / -2=用户未播种 / -3=子限额未播种 / 0=项目池不足 / -10=工作区钱包不足 /
     * -11=子限额不足 / 正数=扣后项目池剩余。
     */
    private static final String FREEZE_LUA =
            "local need=tonumber(ARGV[1]); "
            + "local p=redis.call('get',KEYS[1]); "
            + "if p==false then return -1 end; "
            + "if tonumber(p) < need then return 0 end; "
            + "if ARGV[2]=='1' then "
            + "  local u=redis.call('get',KEYS[2]); "
            + "  if u==false then return -2 end; "
            + "  if tonumber(u) < need then return -10 end; "
            + "end; "
            + "if ARGV[3]=='1' then "
            + "  local t=redis.call('get',KEYS[3]); "
            + "  if t==false then return -3 end; "
            + "  if tonumber(t) < need then return -11 end; "
            + "end; "
            + "if ARGV[2]=='1' then redis.call('decrby',KEYS[2],need) end; "
            + "if ARGV[3]=='1' then redis.call('decrby',KEYS[3],need) end; "
            + "return redis.call('decrby',KEYS[1],need);";

    /**
     * 三级多退少补：incrby 负数=补扣。<b>仅对已存在的 key 生效（exists 守卫）</b>：
     * 余额 key 部署在 noeviction Redis，唯一的缺失来源是管理端 invalidate（停用/删项目·工作区）显式删除。
     * 若此时仍 incrby 会把已被刻意失效的 key 按 0 复活、绕过 seedProject 的 status 复查（复活停用主体 + 抹掉消耗）。
     * 故 key 缺失即跳过该级结算（在途请求的尾结算随失效丢弃·反向同步已在删 key 前快照余额回 DB 基线）。
     */
    private static final String SETTLE_LUA =
            "if ARGV[2]=='1' and redis.call('exists',KEYS[2])==1 then redis.call('incrby',KEYS[2],ARGV[1]) end; "
            + "if ARGV[3]=='1' and redis.call('exists',KEYS[3])==1 then redis.call('incrby',KEYS[3],ARGV[1]) end; "
            + "if redis.call('exists',KEYS[1])==0 then return 0 end; "
            + "return redis.call('incrby',KEYS[1],ARGV[1]);";

    /**
     * 幂等多退少补（"至少一次投递"重放专用·KEYS[4]=去重 key）：dedup 的 SET NX 与三级 incrby 同处
     * 一条 Lua 原子执行——崩溃后重放命中 NX 整体 no-op，绝不双扣/双退。ARGV[4]=去重 key TTL（秒）。
     */
    private static final String SETTLE_ONCE_LUA =
            "if redis.call('set',KEYS[4],'1','NX','EX',ARGV[4])==false then "
            + "return tonumber(redis.call('get',KEYS[1]) or '0') end; "
            + "if ARGV[2]=='1' and redis.call('exists',KEYS[2])==1 then redis.call('incrby',KEYS[2],ARGV[1]) end; "
            + "if ARGV[3]=='1' and redis.call('exists',KEYS[3])==1 then redis.call('incrby',KEYS[3],ARGV[1]) end; "
            + "if redis.call('exists',KEYS[1])==0 then return 0 end; "
            + "return redis.call('incrby',KEYS[1],ARGV[1]);";

    /** 结算去重标记 TTL：覆盖 outbox 最长重试窗口（MAX_ATTEMPTS×退避），到期自动回收。 */
    private static final long SETTLE_DEDUP_TTL_SECONDS = 7L * 24 * 3600;

    private final RedissonClient redisson;
    private final NaiTokenMapper tokenMapper;
    private final NaiProjectMapper projectMapper;
    private final NaiWorkspaceMapper workspaceMapper;

    /**
     * 请求前预扣预估 USD（micro-USD·强闸门）。项目池必查；token 挂 workspace 则工作区钱包同查同扣（B2C 主闸）；
     * token 子限额（quotaUsd&gt;0）有则同查同扣。
     *
     * @return 预扣后项目池余额（micro-USD）
     * @throws GatewayException QUOTA_EXHAUSTED 任一级不足；UNAUTHORIZED 项目/用户不可用
     */
    public long freeze(NaiToken token, long estUsdAmount) {
        String projectCode = requireProject(token);
        if (estUsdAmount <= 0) {
            return projectBalance(projectCode);
        }
        boolean hasWorkspace = token.getWorkspaceId() != null;
        boolean sub = hasSublimit(token);
        for (int round = 1; round <= FREEZE_MAX_ROUNDS; round++) {
            long r = evalFreeze(projectCode, token, hasWorkspace, sub, estUsdAmount);
            if (r == -1L) {
                seedProject(projectCode);
            } else if (r == -2L) {
                seedWorkspaceWallet(projectCode, token.getWorkspaceId());
            } else if (r == -3L) {
                seedTokenSublimit(projectCode, token);
            } else if (r == 0L) {
                throw GatewayException.of(GatewayErrorCode.QUOTA_EXHAUSTED,
                        "项目额度池不足: " + projectCode);
            } else if (r == -10L) {
                throw GatewayException.of(GatewayErrorCode.QUOTA_EXHAUSTED, "工作区钱包余额不足");
            } else if (r == -11L) {
                throw GatewayException.of(GatewayErrorCode.QUOTA_EXHAUSTED,
                        "API Key 子限额已用尽（钱包仍有余额）");
            } else {
                return r;
            }
        }
        throw GatewayException.of(GatewayErrorCode.INTERNAL_ERROR, "USD 余额播种竞态超限: " + projectCode);
    }

    /**
     * 流末多退少补：refund &gt; 0 退回（实际&lt;预估），refund &lt; 0 补扣（实际&gt;预估）。三级同步退补。
     */
    public void settle(NaiToken token, long refund) {
        if (refund == 0) {
            return;
        }
        String projectCode = requireProject(token);
        long balance = evalSettle(projectCode, token, token.getWorkspaceId() != null, hasSublimit(token), refund);
        if (balance < 0) {
            // 🔴 强闸门击穿（评审 H1）：freeze 低估或并发超支致项目池为负。告警，待业务面对账/充值后重播种止血。
            log.warn("⚠️ 项目额度池击穿为负 project={} balance={} refund={}（freeze 低估/并发超支）",
                    projectCode, balance, refund);
        }
    }

    /**
     * 项目池直接预扣（{@code freeze} op·业务面预冻结一笔额度·无 token/user 维度）。
     *
     * @return 预扣后项目池余额
     */
    public long freezeProject(String projectCode, long usdMicro) {
        if (usdMicro <= 0) {
            return projectBalance(projectCode);
        }
        for (int round = 1; round <= FREEZE_MAX_ROUNDS; round++) {
            long r = evalFreeze(projectCode, null, false, false, usdMicro);
            if (r == -1L) {
                seedProject(projectCode);
            } else if (r == 0L) {
                throw GatewayException.of(GatewayErrorCode.QUOTA_EXHAUSTED, "项目额度池不足: " + projectCode);
            } else {
                return r;
            }
        }
        throw GatewayException.of(GatewayErrorCode.INTERNAL_ERROR, "USD 余额播种竞态超限: " + projectCode);
    }

    /**
     * 幂等结算（按业务去重 id 恰好一次）：用于 outbox 重放等「至少一次投递」场景。
     * <p>dedup 标记的 SET NX 与三级 incrby 同处一条 Lua 原子执行——首次执行落标记并扣减，
     * 崩溃后重放命中已存在标记则整体 no-op，<b>绝不双扣/双退</b>（修复 outbox settle 成功但 markDone
     * 失败 → 重放二次结算的资金缺陷）。{@code dedupId} 取 outbox 行 id 等天然唯一键。
     */
    public void settleByTokenIdOnce(Long tokenId, long refund, String dedupId) {
        if (refund == 0) {
            return;
        }
        NaiToken token = tokenMapper.selectById(tokenId);
        if (token == null) {
            log.warn("⚠️ settleOnce 放弃：token 已不存在 tokenId={} refund={}", tokenId, refund);
            return;
        }
        String projectCode = requireProject(token);
        long balance = evalSettleOnce(projectCode, token, token.getWorkspaceId() != null,
                hasSublimit(token), refund, dedupId, SETTLE_DEDUP_TTL_SECONDS);
        if (balance < 0) {
            log.warn("⚠️ 项目额度池击穿为负 project={} balance={} refund={}（outbox 幂等重放）",
                    projectCode, balance, refund);
        }
    }

    /**
     * 幂等结算（持 token·恰好一次）：dedup 标记 SET NX 与三级 incrby 同处一条 Lua 原子执行。
     * <p>内联结算重试 / 终态退款专用：Redis 已执行但 ACK 丢失（超时/failover）时，重试或 outbox 重放命中
     * 已存在标记整体 no-op，<b>绝不双扣/双退</b>（裸 {@code settle} 的 INCRBY 非幂等·重试会二次应用）。
     * dedup 取 {@code "settle:"+requestId}（与 outbox 同 id），TTL 取覆盖「内联重试 + outbox 首次重放」窗口的短值。
     */
    public void settleOnce(NaiToken token, long refund, String dedupId, long ttlSeconds) {
        if (token == null || refund == 0) {
            return;
        }
        String projectCode = requireProject(token);
        long balance = evalSettleOnce(projectCode, token, token.getWorkspaceId() != null,
                hasSublimit(token), refund, dedupId, ttlSeconds);
        if (balance < 0) {
            log.warn("⚠️ 项目额度池击穿为负 project={} balance={} refund={}（幂等内联结算）",
                    projectCode, balance, refund);
        }
    }

    /**
     * 原子充退余额（管理端改额）：key 缺则先按 DB baseline 播种、再 INCRBY delta；返回新余额（micro-USD·可为负=保留超支债务）。
     * <p>🔴 与 {@code settle} 同走 Redis 原子，<b>替代</b>原「读 Redis→写 DB→invalidate(删 Redis)→下次从陈旧 DB 基线重播种」
     * 的 TOCTOU——并发 settle 落在读与失效之间会被重播种覆盖、复活已花额度（少扣）。原子 INCRBY 不丢并发结算。
     */
    public long adjustProject(String projectCode, long baselineMicro, long deltaMicro) {
        return evalAdjust(projectKey(projectCode), baselineMicro, deltaMicro);
    }

    /** 原子充退工作区钱包余额（语义同 {@link #adjustProject}）。 */
    public long adjustWorkspace(String projectCode, Long workspaceId, long baselineMicro, long deltaMicro) {
        return evalAdjust(workspaceKey(projectCode, workspaceId), baselineMicro, deltaMicro);
    }

    /** 项目池当前余额（未播种时回 DB 基线·unlimited 项目回哨兵值）。 */
    public long projectBalance(String projectCode) {
        String v = (String) redisson.getBucket(projectKey(projectCode), StringCodec.INSTANCE).get();
        if (v != null) {
            return Long.parseLong(v);
        }
        NaiProject p = findProject(projectCode);
        if (p == null) {
            return 0L;
        }
        return Boolean.TRUE.equals(p.getUnlimited()) ? UNLIMITED : UsdAmount.toMicros(p.getQuotaUsd());
    }

    /** 工作区钱包当前余额（micro-USD·未播种时回 DB 基线·unlimited 用户回哨兵值）。 */
    public long workspaceBalance(String projectCode, Long workspaceId) {
        String v = (String) redisson.getBucket(workspaceKey(projectCode, workspaceId), StringCodec.INSTANCE).get();
        if (v != null) {
            return Long.parseLong(v);
        }
        NaiWorkspace u = workspaceMapper.selectById(workspaceId);
        if (u == null) {
            return 0L;
        }
        return Boolean.TRUE.equals(u.getUnlimited()) ? UNLIMITED : UsdAmount.toMicros(u.getQuotaUsd());
    }

    /**
     * Key 子限额当前余额（micro-USD）：无子限额→哨兵 unlimited（不参与门控）；未播种→回 DB {@code quotaUsd} 基线。
     * 供 realtime 等需在准入/逐响应时同查子限额的场景用（与 {@code freeze} 的子限额段同口径）。
     */
    public long tokenSublimitBalance(NaiToken token) {
        if (!hasSublimit(token)) {
            return UNLIMITED;
        }
        String pc = requireProject(token);
        String v = (String) redisson.getBucket(tokenKey(pc, token.getId()), StringCodec.INSTANCE).get();
        if (v != null) {
            return Long.parseLong(v);
        }
        return UsdAmount.toMicros(token.getQuotaUsd());
    }

    /** 是否不限额项目（余额展示用·哨兵值不外泄）。 */
    public boolean isUnlimited(String projectCode) {
        NaiProject p = findProject(projectCode);
        return p != null && Boolean.TRUE.equals(p.getUnlimited());
    }

    /** 余额是否哨兵量级（unlimited 播种哨兵·展示层免查库判定，热路径回吐头用）。 */
    public static boolean unlimitedBalance(long balance) {
        return balance >= UNLIMITED / 2;
    }

    /**
     * 失效项目池 Redis 余额（停用/归档/改额后调用，下次请求从 nai_project 重播种）。
     * <p>🔴 删 key 前<b>反向同步</b>：把当前运行时余额原子快照回 DB 基线（quota_usd），再删 key。
     * 否则下次 seedProject 用<b>陈旧</b> DB 基线重播种（消耗只发生在 Redis、两次充退之间从不回写 DB）
     * → 复活已花额度（停用再启用即白送整池余额）。unlimited 哨兵与不存在的 key 不回写。
     * 项目<b>删除</b>场景 DB 行已不存在，此处 update 命中 0 行（无害空转）。
     */
    public void invalidateProject(String projectCode) {
        Object v = redisson.getBucket(projectKey(projectCode), StringCodec.INSTANCE).getAndDelete();
        if (v == null) {
            return;
        }
        long remaining = Long.parseLong((String) v);
        if (unlimitedBalance(remaining)) {
            return;
        }
        projectMapper.update(null, new LambdaUpdateWrapper<NaiProject>()
                .eq(NaiProject::getProjectCode, projectCode)
                .set(NaiProject::getQuotaUsd, UsdAmount.fromMicros(remaining)));
    }

    /**
     * 失效工作区钱包 Redis 余额（停用/改 unlimited 后调用）。反向同步语义同 {@link #invalidateProject}：
     * 删 key 前把运行时余额快照回 nai_workspace.quota_usd，避免重播种复活已花额度。
     */
    public void invalidateWorkspace(String projectCode, Long workspaceId) {
        Object v = redisson.getBucket(workspaceKey(projectCode, workspaceId), StringCodec.INSTANCE).getAndDelete();
        if (v == null) {
            return;
        }
        long remaining = Long.parseLong((String) v);
        if (unlimitedBalance(remaining)) {
            return;
        }
        workspaceMapper.update(null, new LambdaUpdateWrapper<NaiWorkspace>()
                .eq(NaiWorkspace::getId, workspaceId)
                .set(NaiWorkspace::getQuotaUsd, UsdAmount.fromMicros(remaining)));
    }

    /** 失效 Key 子限额 Redis 余额（Key 同步/管理端改子限额后调用）。 */
    public void invalidateToken(String projectCode, Long tokenId) {
        redisson.getBucket(tokenKey(projectCode, tokenId), StringCodec.INSTANCE).delete();
    }

    // ===== 内部 =====

    private static String projectKey(String projectCode) {
        return "nai:usd:{p:" + projectCode + "}";
    }

    private static String workspaceKey(String projectCode, Long workspaceId) {
        return "nai:usd:{p:" + projectCode + "}:w:" + workspaceId;
    }

    private static String tokenKey(String projectCode, Long tokenId) {
        return "nai:usd:{p:" + projectCode + "}:t:" + tokenId;
    }

    /** 结算去重标记 key（与余额 key 同 {p:code} hash tag·cluster 同 slot·Lua 原子成立）。 */
    private static String settledKey(String projectCode, String dedupId) {
        return "nai:usd:{p:" + projectCode + "}:settled:" + dedupId;
    }

    private static boolean hasSublimit(NaiToken token) {
        return token.getQuotaUsd() != null && token.getQuotaUsd().signum() > 0;
    }

    private static String requireProject(NaiToken token) {
        String projectCode = token.getProjectCode();
        if (projectCode == null || projectCode.isBlank()) {
            throw GatewayException.of(GatewayErrorCode.UNAUTHORIZED, "API Key 未归属任何项目");
        }
        return projectCode;
    }

    private long evalFreeze(String projectCode, NaiToken token, boolean hasWorkspace, boolean sub, long est) {
        Long workspaceId = token == null ? 0L : (token.getWorkspaceId() == null ? 0L : token.getWorkspaceId());
        Long tokenId = token == null ? 0L : token.getId();
        Long r = redisson.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE, FREEZE_LUA, RScript.ReturnType.INTEGER,
                List.of(projectKey(projectCode), workspaceKey(projectCode, workspaceId), tokenKey(projectCode, tokenId)),
                String.valueOf(est), hasWorkspace ? "1" : "0", sub ? "1" : "0");
        return r == null ? -1L : r;
    }

    private long evalSettle(String projectCode, NaiToken token, boolean hasWorkspace, boolean sub, long refund) {
        Long workspaceId = token.getWorkspaceId() == null ? 0L : token.getWorkspaceId();
        Long r = redisson.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE, SETTLE_LUA, RScript.ReturnType.INTEGER,
                List.of(projectKey(projectCode), workspaceKey(projectCode, workspaceId), tokenKey(projectCode, token.getId())),
                String.valueOf(refund), hasWorkspace ? "1" : "0", sub ? "1" : "0");
        return r == null ? 0L : r;
    }

    private long evalSettleOnce(String projectCode, NaiToken token, boolean hasWorkspace, boolean sub,
                                long refund, String dedupId, long ttlSeconds) {
        Long workspaceId = token.getWorkspaceId() == null ? 0L : token.getWorkspaceId();
        Long r = redisson.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE, SETTLE_ONCE_LUA, RScript.ReturnType.INTEGER,
                List.of(projectKey(projectCode), workspaceKey(projectCode, workspaceId),
                        tokenKey(projectCode, token.getId()), settledKey(projectCode, dedupId)),
                String.valueOf(refund), hasWorkspace ? "1" : "0", sub ? "1" : "0",
                String.valueOf(ttlSeconds));
        return r == null ? 0L : r;
    }

    /**
     * 原子充退：key 缺按 ARGV[1] baseline 播种 → INCRBY ARGV[2] delta → 返回新余额。
     * <p>🔴 <b>不再下限 0</b>：若运行时余额因强闸门击穿已为负（settle 低估/并发超支），充值须与负债净额
     * （如 -25 充 +10 → -15），<b>绝不重置为 0</b>——否则抹掉已超支债务、令客户白嫖既往超额（资金缺陷）。
     */
    private static final String ADJUST_LUA =
            "if redis.call('exists',KEYS[1])==0 then redis.call('set',KEYS[1],ARGV[1]) end; "
            + "return redis.call('incrby',KEYS[1],ARGV[2]);";

    private long evalAdjust(String key, long baselineMicro, long deltaMicro) {
        Long r = redisson.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE, ADJUST_LUA, RScript.ReturnType.INTEGER,
                List.of(key), String.valueOf(baselineMicro), String.valueOf(deltaMicro));
        return r == null ? 0L : r;
    }

    /** 项目池播种（trySet 防并发重复播种；unlimited→哨兵）。 */
    private void seedProject(String projectCode) {
        NaiProject p = findProject(projectCode);
        if (p == null || p.getStatus() == null || p.getStatus() != 1) {
            throw GatewayException.of(GatewayErrorCode.UNAUTHORIZED, "项目不存在或已归档: " + projectCode);
        }
        long usdMicro = Boolean.TRUE.equals(p.getUnlimited()) ? UNLIMITED : UsdAmount.toMicros(p.getQuotaUsd());
        redisson.getBucket(projectKey(projectCode), StringCodec.INSTANCE).trySet(String.valueOf(usdMicro));
    }

    /** 工作区钱包播种（仅 token 挂 workspace 时走到；停用工作区直接拒）。 */
    private void seedWorkspaceWallet(String projectCode, Long workspaceId) {
        NaiWorkspace u = workspaceMapper.selectById(workspaceId);
        if (u == null || u.getStatus() == null || u.getStatus() != 1) {
            throw GatewayException.of(GatewayErrorCode.UNAUTHORIZED, "工作区不存在或已停用: " + workspaceId);
        }
        long usdMicro = Boolean.TRUE.equals(u.getUnlimited()) ? UNLIMITED : UsdAmount.toMicros(u.getQuotaUsd());
        redisson.getBucket(workspaceKey(projectCode, workspaceId), StringCodec.INSTANCE).trySet(String.valueOf(usdMicro));
    }

    /** Key 子限额播种（仅 hasSublimit 的 token 走到这里）。 */
    private void seedTokenSublimit(String projectCode, NaiToken token) {
        long sublimit = UsdAmount.toMicros(token.getQuotaUsd());
        redisson.getBucket(tokenKey(projectCode, token.getId()), StringCodec.INSTANCE)
                .trySet(String.valueOf(sublimit));
    }

    private NaiProject findProject(String projectCode) {
        return projectMapper.selectOne(new LambdaQueryWrapper<NaiProject>()
                .eq(NaiProject::getProjectCode, projectCode));
    }
}
