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
 * 数据面运行时设置只读视图（nai_system_config·5s Caffeine 挡读）：
 * relay/router 热路径读系统设置（请求记录级别/正文截断等）的零配置侵入入口；
 * 写侧在 nexusai-control 的 control.domain.platform.system SystemConfigService（控制面）。
 *
 * @author nexusai
 */
@Component
@RequiredArgsConstructor
public class RuntimeSettings {

    private final NaiSystemConfigMapper mapper;

    private final LoadingCache<String, Optional<String>> cache = Caffeine.newBuilder()
            .maximumSize(128)
            .expireAfterWrite(Duration.ofSeconds(5))
            .build(this::load);

    private Optional<String> load(String key) {
        NaiSystemConfig row = mapper.selectOne(new LambdaQueryWrapper<NaiSystemConfig>()
                .eq(NaiSystemConfig::getCfgKey, key));
        return Optional.ofNullable(row == null ? null : row.getCfgValue())
                .map(RuntimeSettings::unwrapJsonString)
                .filter(v -> !v.isBlank());
    }

    /** 仅剥离 JSON 字符串字面量的最外层引号，保留值内部的引号（旧实现 {@code replace("\"","")} 会清除全部引号致字符串值损坏）。 */
    private static String unwrapJsonString(String raw) {
        String s = raw.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    public String getString(String key, String def) {
        return cache.get(key).orElse(def);
    }

    public int getInt(String key, int def) {
        try {
            return cache.get(key).map(Integer::parseInt).orElse(def);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean getBool(String key, boolean def) {
        return cache.get(key).map(Boolean::parseBoolean).orElse(def);
    }
}
