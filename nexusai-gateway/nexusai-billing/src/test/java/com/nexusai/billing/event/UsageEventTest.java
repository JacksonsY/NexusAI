package com.nexusai.billing.event;

import com.nexusai.contract.usage.UsageEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * UsageEvent 强类型化的 wire 边界回归（资金链兜底）：
 * ① toMap/fromMap 往返不丢精度；② 脏消息/缺字段逐字段降级为 null·绝不抛异常阻断消费或 outbox。
 */
class UsageEventTest {

    @Test
    void roundTrip_preservesFieldsAndUsdPrecision() {
        UsageEvent src = new UsageEvent("req-1", "proj-A", 1001L, "gpt-4o", 7L, 42L,
                100, 50, 10, 5, 150, new BigDecimal("0.001234"), "parsed");

        UsageEvent back = UsageEvent.fromMap(src.toMap());

        assertEquals(src, back);                                   // 全字段往返一致
        assertEquals(new BigDecimal("0.001234"), back.costUsd());  // micro-USD 6 位精度不丢
    }

    @Test
    void toMap_encodesNullsAsEmptyOrZero() {
        UsageEvent e = new UsageEvent("req-2", "proj-B", null, "m", null, null,
                null, null, null, null, null, null, null);
        Map<String, String> m = e.toMap();
        assertEquals("", m.get(UsageEvent.F_WORKSPACE_ID));   // workspaceId/null → 空串（B2B 兼容）
        assertEquals("", m.get(UsageEvent.F_CHANNEL_ID));
        assertEquals("0", m.get(UsageEvent.F_PROMPT_TOKENS)); // token/null → 0
        assertEquals("0", m.get(UsageEvent.F_COST_USD));
    }

    @Test
    void fromMap_dirtyAndMissingFields_degradeToNull_neverThrow() {
        Map<String, String> dirty = new HashMap<>();
        dirty.put(UsageEvent.F_REQUEST_ID, "req-3");
        dirty.put(UsageEvent.F_WORKSPACE_ID, "not-a-number");   // 脏数值
        dirty.put(UsageEvent.F_PROMPT_TOKENS, "");              // 空串
        dirty.put(UsageEvent.F_COST_USD, "abc");                // 脏金额
        // model/channelId/keyId 等键缺失

        UsageEvent e = assertDoesNotThrow(() -> UsageEvent.fromMap(dirty));
        assertEquals("req-3", e.requestId());
        assertNull(e.workspaceId());           // 脏数值降级
        assertNull(e.promptTokens());          // 空串降级
        assertNull(e.costUsd());               // 脏金额降级（计费由 nai_usage_log 权威账本兜底）
        assertNull(e.model());                 // 缺字段降级
    }

    @Test
    void fromMap_null_returnsNull() {
        assertNull(UsageEvent.fromMap(null));
    }
}
