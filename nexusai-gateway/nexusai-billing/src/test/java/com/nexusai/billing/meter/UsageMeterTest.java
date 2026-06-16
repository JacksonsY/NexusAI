package com.nexusai.billing.meter;

import com.nexusai.billing.config.UsdRate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class UsageMeterTest {

    private final UsageMeter meter = new UsageMeter();

    @Test
    void computesCostInMicroUsdWithStructuredRates() {
        UsdRate price = new UsdRate(
                new BigDecimal("2"),       // input  $/M（=0.000002 USD/token）
                new BigDecimal("8"),       // output $/M
                new BigDecimal("0.5"),     // cachedInput $/M
                new BigDecimal("2.5"),     // writeCached $/M
                new BigDecimal("0.001000"),    // perRequest USD（绝对值·不折百万）
                new BigDecimal("0.020000"),    // perImage   USD（绝对值·不折百万）
                false);                        // unknown=false（已建价）

        // prompt=1000（含 cached100 + creation20 → nonCached880）, completion=200, images=1
        // tokenUsd = (2*880 + 0.5*100 + 2.5*20 + 8*200) ÷ 1e6 = 3460 ÷ 1e6 = 0.00346
        // usd = 0.00346 + 0.02*1 + 0.001 = 0.02446 → ×1e6 = 24460 micro-USD
        long microUsd = meter.microUsdFor(price, 1_000, 200, 100, 20, 1);

        assertThat(microUsd).isEqualTo(24_460L);
    }

    @Test
    void roundsAnyFractionalMicroUsdUp() {
        UsdRate price = new UsdRate(
                new BigDecimal("0.1"),         // 0.1 $/M（=0.0000001 USD/token → 0.1 micro-USD/token）
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, false);

        // 0.1 $/M × 1 token ÷ 1e6 = 0.0000001 USD = 0.1 micro-USD → CEILING = 1（防少扣）
        assertThat(meter.microUsdFor(price, 1, 0, 0, 0, 0)).isEqualTo(1L);
    }
}
