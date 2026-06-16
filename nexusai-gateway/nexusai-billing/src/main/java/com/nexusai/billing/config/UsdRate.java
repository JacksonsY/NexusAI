package com.nexusai.billing.config;

import java.math.BigDecimal;

/**
 * 结构化价格：token 类费率单位 <b>USD / 百万 token（$/M）</b>，按图每张、按次每次为<b>绝对 USD</b>。
 * 现代供应商 input/output 价差 3–5 倍、cached 单独价、image 按张——单一扁平费率会在
 * output-heavy 流量系统性亏钱、对 embedding/image 直接算错。
 * <p>价格真理源在 {@code nai_model}；网关只用此结构本地精确预扣/结算（{@link com.nexusai.billing.meter.UsageMeter}
 * 将 $/M 费率 ÷10^6 折回 USD、再折算为 micro-USD 整数当量交强闸门），<b>绝不计价</b>。
 * <p><b>单位口径（2026-06-15 统一）</b>：$/M 与 models.dev、控制台展示、录入表单同口径，全链零边界换算；
 * 唯一折算点在 {@link com.nexusai.billing.meter.UsageMeter#microUsdFor}。
 * <p>字段单位：token 类按 <b>USD/百万 token</b>；{@code perImageFee} 按 USD/张；{@code perRequestFee} 按 USD/次。
 *
 * @param inputRate       每<b>百万</b>非缓存 prompt token 的 USD（$/M）
 * @param outputRate      每<b>百万</b> completion token 的 USD（$/M）
 * @param cachedInputRate 每<b>百万</b>缓存命中 prompt token 的 USD（通常远低于 inputRate）
 * @param writeCachedRate 每<b>百万</b>缓存创建写入 token 的 USD（Anthropic 约 inputRate×1.25·缺省回退 inputRate）
 * @param perRequestFee   每次请求固定 USD（绝对值）
 * @param perImageFee     每张图固定 USD（image 模态·绝对值）
 * @param unknown         未建价哨兵判别位（true 仅 {@link #UNKNOWN}）：用<b>判别字段</b>而非对象身份区分
 *                        「未建价」与「显式零价」，经 equals/序列化/集合往返仍稳健，杜绝 deny-unpriced 闸门误判
 * @author nexusai
 */
public record UsdRate(BigDecimal inputRate, BigDecimal outputRate, BigDecimal cachedInputRate,
                         BigDecimal writeCachedRate, BigDecimal perRequestFee, BigDecimal perImageFee,
                         boolean unknown) {

    /** 缺省价格（全 0）：仅用于 null 入参兜底与「显式免费模型」（nai_model 有行但费率全 0）。 */
    public static final UsdRate DEFAULT =
            new UsdRate(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, false);

    /**
     * 未建价哨兵（nai_model 无该模型行）：与 {@link #DEFAULT}（显式全 0=免费模型）以 {@code unknown=true} <b>判别字段区分</b>
     * （非对象身份·equals/序列化/集合往返仍可区分），供强闸门识别「可路由但未配置价格」的模型并拒绝计费——
     * 否则 freeze≈0 形同虚设、settle 按 0 计＝白嫖。
     */
    public static final UsdRate UNKNOWN =
            new UsdRate(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, true);

    /** 是否未建价哨兵（判别字段·非身份比较；显式零价行经 {@link #of} 构造 {@code unknown=false} 不命中）。 */
    public boolean isUnknown() {
        return unknown;
    }

    /**
     * 规整构造（已建价·{@code unknown=false}）：null 字段补默认（input 缺省 0；output 缺省回退 input；
     * writeCached 缺省回退 input；cached/perRequest/perImage 缺省 0）。
     */
    public static UsdRate of(BigDecimal input, BigDecimal output, BigDecimal cachedInput,
                                BigDecimal writeCached, BigDecimal perRequest, BigDecimal perImage) {
        BigDecimal in = input == null ? BigDecimal.ZERO : input;
        BigDecimal out = output == null ? in : output;
        return new UsdRate(
                in, out,
                cachedInput == null ? BigDecimal.ZERO : cachedInput,
                writeCached == null ? in : writeCached,
                perRequest == null ? BigDecimal.ZERO : perRequest,
                perImage == null ? BigDecimal.ZERO : perImage,
                false);
    }
}
