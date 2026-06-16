package com.nexusai.billing.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class UsdAmountTest {

    @Test
    void convertsUsdToMicrosWithCeilingRounding() {
        assertThat(UsdAmount.toMicros(new BigDecimal("1.000001"))).isEqualTo(1_000_001L);
        assertThat(UsdAmount.toMicros(new BigDecimal("0.0000001"))).isEqualTo(1L);
        assertThat(UsdAmount.toMicros(new BigDecimal("-0.0000001"))).isEqualTo(-1L);
    }

    @Test
    void convertsMicrosBackToUsdScale() {
        assertThat(UsdAmount.fromMicros(1_234_567L)).isEqualByComparingTo("1.234567");
        assertThat(UsdAmount.fromMicros(0L)).isEqualByComparingTo("0.000000");
    }
}
