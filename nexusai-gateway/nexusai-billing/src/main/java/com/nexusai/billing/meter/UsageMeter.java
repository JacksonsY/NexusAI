package com.nexusai.billing.meter;

import com.nexusai.billing.config.UsdRate;
import com.nexusai.billing.money.UsdAmount;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 计量：按结构化 USD 价格（{@link UsdRate}·来自 nai_model）分别对 input/cached/output token + 按图/按次计算成本，
 * 折算为 <b>micro-USD 整数当量</b>（{@link UsdAmount#toMicros}·UP 取整防少扣）交强闸门。金额 BigDecimal、
 * 账本 micro-USD 整数——铁律避开 float。
 * <p><b>单位口径（2026-06-15 统一）</b>：token 费率 {@code inputRate/outputRate/cachedInputRate/writeCachedRate}
 * 一律为 <b>USD / 百万 token（$/M）</b>——与 models.dev、控制台展示、录入表单同口径，单一折算点在此，避免跨边界换算漂移。
 * token 成本 = 费率 × tokens ÷ 10^6（{@link BigDecimal#movePointLeft}·除 10^6 必为有限小数·精确无损）；
 * {@code perImageFee}/{@code perRequestFee} 仍为<b>绝对 USD</b>（按张/按次），不参与百万折算。
 *
 * @author nexusai
 */
@Service
public class UsageMeter {

    /** token 费率单位换算因子：$/M → $/token 即 ÷10^6（{@link BigDecimal#movePointLeft}，除 10 的幂必精确）。 */
    private static final int PER_MILLION_SCALE = 6;

    /**
     * 结构化计费（$/M 费率 → micro-USD 整数）：
     * <pre>tokenUsd = (inputRate·(prompt-cached-creation) + cachedInputRate·cached + writeCachedRate·creation
     *            + outputRate·completion) ÷ 10^6        // token 费率为 $/M，÷10^6 折回 USD
     * usd      = tokenUsd + perImageFee·images + perRequestFee   // 按张/按次为绝对 USD
     * micro    = ⌈ usd × 10^6 ⌉（UsdAmount.toMicros·UP）</pre>
     *
     * @param rate        结构化价格（token 费率 $/M·按张/按次绝对 USD；null 用 DEFAULT 保守 0）
     * @param promptTokens     prompt token（含 cached 与 creation）
     * @param completionTokens completion token
     * @param cachedTokens     缓存读命中 token（从 promptTokens 拆出按 cachedInputRate 计）
     * @param cacheCreationTokens 缓存创建写入 token（从 promptTokens 拆出按 writeCachedRate 计·Anthropic 口径）
     * @param images           图片张数（image 模态；token 类传 0）
     * @return 应扣 micro-USD（向上取整）
     */
    public long microUsdFor(UsdRate rate, int promptTokens, int completionTokens,
                            int cachedTokens, int cacheCreationTokens, int images) {
        UsdRate r = rate == null ? UsdRate.DEFAULT : rate;
        int prompt = Math.max(0, promptTokens);
        int cached = Math.max(0, Math.min(cachedTokens, prompt));
        int creation = Math.max(0, Math.min(cacheCreationTokens, prompt - cached));
        int nonCached = prompt - cached - creation;
        // token 费率为 $/M：先求 费率·tokens 再 ÷10^6 折回 USD（movePointLeft 精确无舍入）
        BigDecimal tokenUsd = r.inputRate().multiply(BigDecimal.valueOf(nonCached))
                .add(r.cachedInputRate().multiply(BigDecimal.valueOf(cached)))
                .add(r.writeCachedRate().multiply(BigDecimal.valueOf(creation)))
                .add(r.outputRate().multiply(BigDecimal.valueOf(Math.max(0, completionTokens))))
                .movePointLeft(PER_MILLION_SCALE);
        // 按张/按次为绝对 USD，不折百万
        BigDecimal usd = tokenUsd
                .add(r.perImageFee().multiply(BigDecimal.valueOf(Math.max(0, images))))
                .add(r.perRequestFee());
        return UsdAmount.toMicros(usd);
    }
}
