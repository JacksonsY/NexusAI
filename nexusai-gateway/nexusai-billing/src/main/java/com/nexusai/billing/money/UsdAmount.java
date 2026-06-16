package com.nexusai.billing.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * USD ↔ micro-USD 单一换算真源（计费铁律：余额账本走 micro-USD 整数原子运算·禁 float；
 * 持久化/展示走 BigDecimal USD 精确十进制·DECIMAL(18,6)）。
 * <p>采用整数额度模式（本网关 1 USD = 1,000,000 micro-USD）：整数额度避开浮点累加误差，float USD 会有精度漂移。
 * <p>{@link #toMicros} 取整用 {@link RoundingMode#UP}（远离 0·正费用向上取整不少扣、负退款足额退）。
 *
 * @author nexusai
 */
public final class UsdAmount {

    private UsdAmount() {
    }

    /** USD 小数位（DECIMAL(18,6)·micro-USD 即 10^SCALE 当量）。 */
    public static final int SCALE = 6;

    /** 1 USD 含的 micro-USD 数（10^6）。 */
    public static final BigDecimal MICROS = BigDecimal.valueOf(1_000_000L);

    /** USD（BigDecimal）→ micro-USD（long·RoundingMode.UP 远离 0·不少扣）。null→0。 */
    public static long toMicros(BigDecimal usd) {
        if (usd == null) {
            return 0L;
        }
        return usd.multiply(MICROS).setScale(0, RoundingMode.UP).longValueExact();
    }

    /** micro-USD（long）→ USD（BigDecimal·定标 6dp·精确无舍入）。 */
    public static BigDecimal fromMicros(long micros) {
        return BigDecimal.valueOf(micros, SCALE);
    }

    /** micro-USD（long）→ USD 平铺串（响应头/日志展示·如 "0.001234"）。 */
    public static String format(long micros) {
        return fromMicros(micros).toPlainString();
    }
}
