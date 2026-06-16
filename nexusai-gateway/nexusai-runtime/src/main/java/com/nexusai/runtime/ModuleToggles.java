package com.nexusai.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.nexusai.persistence.mapper.NaiSystemConfigMapper;
import com.nexusai.persistence.po.NaiSystemConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 功能模块运行时开关：读 nai_system_config 的
 * {@code module.{name}.enabled}（5s Caffeine 挡读·改开关 5s 内生效）；未配置回退装配默认值。
 * 归 nexusai-runtime 模块（数据面运行时配置挡读·relay/router 共享）供数据面 gate 调用。
 *
 * @author nexusai
 */
@Component
@RequiredArgsConstructor
public class ModuleToggles {

    private final NaiSystemConfigMapper mapper;

    private final LoadingCache<String, Optional<Boolean>> cache = Caffeine.newBuilder()
            .maximumSize(64)
            .expireAfterWrite(Duration.ofSeconds(5))
            .build(this::load);

    private Optional<Boolean> load(String name) {
        NaiSystemConfig row = mapper.selectOne(new LambdaQueryWrapper<NaiSystemConfig>()
                .eq(NaiSystemConfig::getCfgKey, "module." + name + ".enabled"));
        if (row == null || row.getCfgValue() == null || row.getCfgValue().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Boolean.parseBoolean(row.getCfgValue().replace("\"", "").trim()));
    }

    /** 模块是否启用（未配置 → defaultEnabled）。 */
    public boolean enabled(String name, boolean defaultEnabled) {
        return cache.get(name).orElse(defaultEnabled);
    }

    public void invalidate(String name) {
        cache.invalidate(name);
    }
}
