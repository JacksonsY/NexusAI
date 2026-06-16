package com.nexusai.billing.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nexusai.common.event.ConfigChangedEvent;
import com.nexusai.persistence.mapper.NaiModelMapper;
import com.nexusai.persistence.po.NaiModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 模型价格读取服务：读 nai_model 当前结构化 USD 价格，供强闸门预扣/结算（{@code QuotaGateFilter} 等热路径每请求调用）。
 * <p><b>Caffeine 短 TTL 本地缓存</b>（by modelKey·默认 30s·与 {@link com.nexusai.router.model 同节奏的} 别名快照对齐）：
 * 消除热路径每请求一次 {@code selectOne} 打 DB。缺失行缓存为 {@link UsdRate#UNKNOWN}（负缓存·避免未知模型反复查库·
 * 且与显式零价行身份区分供强闸门拒未建价模型）。失效驱动：模型价格/状态经控制台编辑 → {@link ConfigChangedEvent}
 * （CONFIG/ALL 域·含跨实例广播）整体清空，价格改动即时生效，不靠 TTL 兜底。
 *
 * @author nexusai
 */
@Slf4j
@Service
public class ModelPriceService {

    private final NaiModelMapper modelMapper;
    private final Cache<String, UsdRate> priceCache;

    public ModelPriceService(NaiModelMapper modelMapper,
                             @Value("${nexusai.cache.model-price.ttl-seconds:30}") long ttlSeconds,
                             @Value("${nexusai.cache.model-price.max-size:5000}") long maxSize) {
        this.modelMapper = modelMapper;
        this.priceCache = Caffeine.newBuilder().recordStats()
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .maximumSize(maxSize)
                .build();
    }

    public UsdRate priceFor(String model) {
        if (model == null || model.isBlank()) {
            return UsdRate.DEFAULT;
        }
        return priceCache.get(model, this::load);
    }

    private UsdRate load(String model) {
        NaiModel row = modelMapper.selectOne(new LambdaQueryWrapper<NaiModel>()
                .select(NaiModel::getInputRate,
                        NaiModel::getOutputRate,
                        NaiModel::getCachedInputRate,
                        NaiModel::getWriteCachedRate,
                        NaiModel::getPerRequestFee,
                        NaiModel::getPerImageFee)
                .eq(NaiModel::getModelKey, model));
        return row == null ? UsdRate.UNKNOWN : toPrice(row);
    }

    /** 配置变更 → 整体清空价格缓存（CONFIG/ALL 域）；模型价格/状态编辑即时生效。 */
    @EventListener
    public void onConfigChanged(ConfigChangedEvent event) {
        if (event.hits(ConfigChangedEvent.Scope.CONFIG)) {
            priceCache.invalidateAll();
            log.info("ModelPriceService 价格缓存已清空（CONFIG version={}）", event.version());
        }
    }

    private static UsdRate toPrice(NaiModel m) {
        return UsdRate.of(m.getInputRate(), m.getOutputRate(),
                m.getCachedInputRate(), m.getWriteCachedRate(), m.getPerRequestFee(), m.getPerImageFee());
    }
}
