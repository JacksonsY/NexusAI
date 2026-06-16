package com.nexusai.control.domain.platform.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.persistence.mapper.NaiSystemConfigMapper;
import com.nexusai.persistence.po.NaiSystemConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 系统配置 KV 服务：值为 JSON 字面量字符串；
 * 读路径 5s Caffeine 挡读（运行时组件按需热读·改配置 5s 内生效）。
 *
 * @author nexusai
 */
@Service
@RequiredArgsConstructor
public class SystemConfigService {

    private final NaiSystemConfigMapper mapper;

    private final LoadingCache<String, Optional<String>> cache = Caffeine.newBuilder()
            .maximumSize(512)
            .expireAfterWrite(Duration.ofSeconds(5))
            .build(this::loadRaw);

    private Optional<String> loadRaw(String key) {
        NaiSystemConfig row = mapper.selectOne(new LambdaQueryWrapper<NaiSystemConfig>()
                .eq(NaiSystemConfig::getCfgKey, key));
        return Optional.ofNullable(row == null ? null : row.getCfgValue());
    }

    /** 全量列表（原始 JSON 字面量）。 */
    public Map<String, Object> listAll() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<NaiSystemConfig> rows = mapper.selectList(null);
        for (NaiSystemConfig row : rows) {
            out.put(row.getCfgKey(), parse(row.getCfgValue()));
        }
        return out;
    }

    public Object getParsed(String key) {
        return cache.get(key).map(SystemConfigService::parse).orElse(null);
    }

    public String getString(String key, String def) {
        Object v = getParsed(key);
        return v == null ? def : String.valueOf(v);
    }

    public int getInt(String key, int def) {
        Object v = getParsed(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? def : Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean getBool(String key, boolean def) {
        Object v = getParsed(key);
        if (v instanceof Boolean b) {
            return b;
        }
        return v == null ? def : Boolean.parseBoolean(String.valueOf(v));
    }

    public Long getLong(String key) {
        Object v = getParsed(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? null : Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** upsert（value 为已解析对象·序列化为 JSON 字面量存储）。 */
    public void put(String key, Object value, String description) {
        String json = JsonUtils.toJson(value);
        NaiSystemConfig row = mapper.selectOne(new LambdaQueryWrapper<NaiSystemConfig>()
                .eq(NaiSystemConfig::getCfgKey, key));
        if (row == null) {
            row = new NaiSystemConfig();
            row.setCfgKey(key);
            row.setCfgValue(json);
            row.setDescription(description);
            mapper.insert(row);
        } else {
            row.setCfgValue(json);
            if (description != null) {
                row.setDescription(description);
            }
            mapper.updateById(row);
        }
        cache.invalidate(key);
    }

    public void delete(String key) {
        mapper.delete(new LambdaQueryWrapper<NaiSystemConfig>().eq(NaiSystemConfig::getCfgKey, key));
        cache.invalidate(key);
    }

    private static Object parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JsonUtils.parse(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }
}
