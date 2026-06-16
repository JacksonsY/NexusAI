package com.nexusai.limit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * 并发槽（ZSET + Lua 原子占释）。两类槽共用同一原子算法、不同命名空间：
 * <ul>
 *   <li><b>上游渠道密钥槽</b> {@code nai:slot:{keyId}}：硬限单渠道密钥并发（第二级选号④层）；</li>
 *   <li><b>入站 Key 槽</b> {@code nai:islot:{tokenId}}：硬限单 sk- 客户端在途请求数（{@code nai_token.concurrent_limit}·
 *       ），防慢请求长尾绕过 RPM 堆积上游连接。</li>
 * </ul>
 * 每槽一个有序集：member=占用令牌、score=Redis 服务端到期戳。占用前按 score 清理超时占用（防进程崩溃漏释泄漏），
 * 再判 zcard&lt;max 原子 zadd——释放=zrem。
 *
 * @author nexusai
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConcurrencySlot {

    private static final String KEY_PREFIX = "nai:slot:";
    /** 入站 Key 维度并发槽前缀 */
    private static final String INBOUND_PREFIX = "nai:islot:";
    /**
     * 槽租约（崩溃漏释的兜底回收窗口·<b>非</b>单请求时限）。必须 <b>大于任何合法请求耗时</b>——否则长流式
     * 补全（&gt;租约）的在途槽会被提前回收、并发被超卖（真实在途数突破上限）。默认 600s 覆盖绝大多数流；
     * 超长流式/推理可调 {@code nexusai.limit.slot-lease-ms}。正常释放走 {@code release}/{@code releaseInbound}，
     * 租约只在进程崩溃漏释时兜底。
     */
    @Value("${nexusai.limit.slot-lease-ms:600000}")
    private long leaseMs;

    /**
     * 清理超时 → 判容量 → 占用；返回 1 成功 / 0 已满。
     * <p><b>时间基准取 Redis 服务端时钟（{@code redis.call('TIME')}），不传 JVM 时间</b>：
     * 多实例 JVM 时钟漂移会使「写入的到期戳」与「清理基准」在不同实例间
     * 不一致 → 时钟快的实例占的槽被别的实例提前清掉（超卖）/时钟慢则租约迟迟不回收（少卖）。服务端时钟统一消除该漂移。
     * {@code redis.replicate_commands()} 兼容 Redis 5/6 的非确定性命令复制（Redis 7 默认 effects 复制·此调用为 no-op）。
     */
    private static final String OCCUPY_LUA =
            "redis.replicate_commands(); "
            + "local t = redis.call('TIME'); "
            + "local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000); "
            + "redis.call('zremrangebyscore', KEYS[1], 0, now); "
            + "local n = redis.call('zcard', KEYS[1]); "
            + "if n < tonumber(ARGV[1]) then "
            + "  redis.call('zadd', KEYS[1], now + tonumber(ARGV[2]), ARGV[3]); "
            + "  redis.call('pexpire', KEYS[1], ARGV[2]); "
            + "  return 1; "
            + "end; return 0;";

    private final RedissonClient redisson;

    /**
     * 尝试为 Key 占用一个并发槽。
     *
     * @param keyId          Key id
     * @param maxConcurrency 并发上限（&lt;=0 表示不限并发，直接放行不占槽）
     * @param token          占用令牌（调用方生成的唯一串，释放时回传）
     * @return true 占用成功 / false 槽已满
     */
    public boolean tryOccupy(Long keyId, int maxConcurrency, String token) {
        return occupy(KEY_PREFIX + keyId, maxConcurrency, token);
    }

    /** 释放上游渠道密钥并发槽（zrem 令牌）。token 为占位（不限并发时）则空操作。 */
    public void release(Long keyId, String token) {
        releaseSlot(KEY_PREFIX + keyId, token);
    }

    /** 当前占用槽数（运维观测·含尚未清理的租约·近似值）。异常返回 -1。 */
    public int occupied(Long keyId) {
        try {
            return redisson.getScoredSortedSet(KEY_PREFIX + keyId, StringCodec.INSTANCE).size();
        } catch (Exception e) {
            // 仅运维观测非关键路径：Redis 抖动时返回 -1 哨兵不阻断，debug 记录便于排查
            log.debug("占用槽数观测失败 keyId={}: {}", keyId, e.toString());
            return -1;
        }
    }

    /** 尝试占用入站 Key 维度并发槽（{@code nai_token.concurrent_limit}）。 */
    public boolean tryOccupyInbound(Long tokenId, int maxConcurrency, String token) {
        return occupy(INBOUND_PREFIX + tokenId, maxConcurrency, token);
    }

    /** 释放入站 Key 维度并发槽。 */
    public void releaseInbound(Long tokenId, String token) {
        releaseSlot(INBOUND_PREFIX + tokenId, token);
    }

    /** 原子占用核心（按完整 redis key 复用同一 Lua）。maxConcurrency&lt;=0 直接放行不占槽。 */
    private boolean occupy(String redisKey, int maxConcurrency, String token) {
        if (maxConcurrency <= 0) {
            return true;
        }
        Long r = redisson.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE, OCCUPY_LUA, RScript.ReturnType.INTEGER,
                Collections.singletonList(redisKey),
                String.valueOf(maxConcurrency), String.valueOf(leaseMs), token);
        return r != null && r == 1L;
    }

    /** 释放核心（zrem 令牌·空 token 空操作·失败由租约兜底回收）。 */
    private void releaseSlot(String redisKey, String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        try {
            redisson.getScoredSortedSet(redisKey, StringCodec.INSTANCE).remove(token);
        } catch (Exception ignored) {
            // 释放失败由租约到期兜底回收
        }
    }
}
