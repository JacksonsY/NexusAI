package com.nexusai.control.domain.platform.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.persistence.log.RequestLogStore;
import com.nexusai.persistence.mapper.NaiAbilityMapper;
import com.nexusai.persistence.mapper.NaiChannelKeyMapper;
import com.nexusai.persistence.mapper.NaiChannelMapper;
import com.nexusai.persistence.mapper.NaiFailoverEventMapper;
import com.nexusai.persistence.mapper.NaiModelMapper;
import com.nexusai.persistence.mapper.NaiProjectMapper;
import com.nexusai.persistence.mapper.NaiRoutingGroupMapper;
import com.nexusai.persistence.mapper.NaiSystemConfigMapper;
import com.nexusai.persistence.mapper.NaiTaskMapper;
import com.nexusai.persistence.mapper.NaiUsageLogMapper;
import com.nexusai.persistence.po.NaiAbility;
import com.nexusai.persistence.po.NaiChannel;
import com.nexusai.persistence.po.NaiChannelKey;
import com.nexusai.persistence.po.NaiModel;
import com.nexusai.persistence.po.NaiProject;
import com.nexusai.persistence.po.NaiRoutingGroup;
import com.nexusai.persistence.po.NaiSystemConfig;
import com.nexusai.control.domain.platform.system.vo.ImportEntityStatVo;
import com.nexusai.control.domain.platform.system.vo.PurgeResultVo;
import com.nexusai.control.domain.platform.system.vo.SystemVersionVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringBootVersion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.management.ManagementFactory;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 系统维护（系统配置导出/导入 + 清空·按本系统实体映射）：
 * 配置导出/导入（渠道/渠道密钥/模型/能力/调度组/项目/系统配置）+ 清空（使用记录/请求日志/任务/故障转移事件）。
 *
 * @author nexusai
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemMaintenanceService {

    /** 缺省版本号（jar 未注入 Implementation-Version 时回落） */
    private static final String DEFAULT_VERSION = "0.0.1-SNAPSHOT";
    /** 请求日志清空分批大小（防长事务） */
    private static final int REQUEST_LOG_PURGE_BATCH = 5000;

    private final NaiChannelMapper channelMapper;
    private final NaiChannelKeyMapper channelKeyMapper;
    private final NaiModelMapper modelMapper;
    private final NaiAbilityMapper abilityMapper;
    private final NaiRoutingGroupMapper routingGroupMapper;
    private final NaiProjectMapper projectMapper;
    private final NaiSystemConfigMapper systemConfigMapper;
    private final NaiUsageLogMapper usageLogMapper;
    private final NaiTaskMapper taskMapper;
    private final NaiFailoverEventMapper failoverEventMapper;
    private final RequestLogStore requestLogStore;

    /** 系统版本信息（系统信息 section）。 */
    public SystemVersionVo version() {
        String implVersion = SystemMaintenanceService.class.getPackage().getImplementationVersion();
        return new SystemVersionVo(
                implVersion == null ? DEFAULT_VERSION : implVersion,
                SpringBootVersion.getVersion(),
                System.getProperty("java.version"),
                ManagementFactory.getRuntimeMXBean().getUptime(),
                ManagementFactory.getRuntimeMXBean().getStartTime());
    }

    /** 配置导出（凭证保持 AES 密文·同 crypto keys 部署可直接还原）。 */
    public Map<String, Object> exportConfig() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("exportedAt", LocalDateTime.now().toString());
        data.put("channels", channelMapper.selectList(null));
        data.put("channelKeys", channelKeyMapper.selectList(null));
        data.put("models", modelMapper.selectList(null));
        data.put("abilities", abilityMapper.selectList(null));
        data.put("routingGroups", routingGroupMapper.selectList(null));
        data.put("projects", projectMapper.selectList(null));
        data.put("systemConfigs", systemConfigMapper.selectList(null));
        return data;
    }

    /**
     * 配置导入（mergeMode：skip=同 id 跳过 / overwrite=同 id 覆盖）。
     *
     * @return 各实体导入计数
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> importConfig(Map<String, Object> payload, String mergeMode) {
        boolean overwrite = "overwrite".equalsIgnoreCase(mergeMode);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("channels", importList(payload, "channels", new TypeReference<List<NaiChannel>>() {
        }, NaiChannel::getId, channelMapper::selectById, channelMapper::insert, channelMapper::updateById, overwrite));
        stats.put("channelKeys", importList(payload, "channelKeys", new TypeReference<List<NaiChannelKey>>() {
        }, NaiChannelKey::getId, channelKeyMapper::selectById, channelKeyMapper::insert, channelKeyMapper::updateById, overwrite));
        stats.put("models", importList(payload, "models", new TypeReference<List<NaiModel>>() {
        }, NaiModel::getId, modelMapper::selectById, modelMapper::insert, modelMapper::updateById, overwrite));
        stats.put("abilities", importAbilities(payload, overwrite));
        stats.put("routingGroups", importList(payload, "routingGroups", new TypeReference<List<NaiRoutingGroup>>() {
        }, NaiRoutingGroup::getId, routingGroupMapper::selectById, routingGroupMapper::insert, routingGroupMapper::updateById, overwrite));
        stats.put("projects", importList(payload, "projects", new TypeReference<List<NaiProject>>() {
        }, NaiProject::getId, projectMapper::selectById, projectMapper::insert, projectMapper::updateById, overwrite));
        stats.put("systemConfigs", importSystemConfigs(payload, overwrite));
        return stats;
    }

    private <T> ImportEntityStatVo importList(Map<String, Object> payload, String key,
                                              TypeReference<List<T>> type,
                                              Function<T, Object> idGetter,
                                              Function<java.io.Serializable, T> selectById,
                                              Function<T, Integer> insert,
                                              Function<T, Integer> updateById,
                                              boolean overwrite) {
        Object raw = payload.get(key);
        int created = 0;
        int updated = 0;
        int skipped = 0;
        if (raw != null) {
            List<T> rows = JsonUtils.parse(JsonUtils.toJson(raw), type);
            for (T row : rows) {
                Object id = idGetter.apply(row);
                T existing = id == null ? null : selectById.apply((java.io.Serializable) id);
                if (existing == null) {
                    insert.apply(row);
                    created++;
                } else if (overwrite) {
                    updateById.apply(row);
                    updated++;
                } else {
                    skipped++;
                }
            }
        }
        return new ImportEntityStatVo(created, updated, skipped);
    }

    /** 能力表无单列主键（groupCode+model+channelId 复合身份）→ 按复合键去重导入。 */
    private ImportEntityStatVo importAbilities(Map<String, Object> payload, boolean overwrite) {
        Object raw = payload.get("abilities");
        int created = 0;
        int updated = 0;
        int skipped = 0;
        if (raw != null) {
            List<NaiAbility> rows = JsonUtils.parse(JsonUtils.toJson(raw), new TypeReference<>() {
            });
            for (NaiAbility row : rows) {
                LambdaQueryWrapper<NaiAbility> w = new LambdaQueryWrapper<NaiAbility>()
                        .eq(NaiAbility::getGroupCode, row.getGroupCode())
                        .eq(NaiAbility::getModel, row.getModel())
                        .eq(NaiAbility::getChannelId, row.getChannelId());
                NaiAbility existing = abilityMapper.selectOne(w);
                if (existing == null) {
                    abilityMapper.insert(row);
                    created++;
                } else if (overwrite) {
                    abilityMapper.update(row, w);
                    updated++;
                } else {
                    skipped++;
                }
            }
        }
        return new ImportEntityStatVo(created, updated, skipped);
    }

    private ImportEntityStatVo importSystemConfigs(Map<String, Object> payload, boolean overwrite) {
        Object raw = payload.get("systemConfigs");
        int created = 0;
        int updated = 0;
        int skipped = 0;
        if (raw != null) {
            List<NaiSystemConfig> rows = JsonUtils.parse(JsonUtils.toJson(raw), new TypeReference<>() {
            });
            for (NaiSystemConfig row : rows) {
                NaiSystemConfig existing = systemConfigMapper.selectOne(new LambdaQueryWrapper<NaiSystemConfig>()
                        .eq(NaiSystemConfig::getCfgKey, row.getCfgKey()));
                if (existing == null) {
                    row.setId(null);
                    systemConfigMapper.insert(row);
                    created++;
                } else if (overwrite) {
                    existing.setCfgValue(row.getCfgValue());
                    existing.setDescription(row.getDescription());
                    systemConfigMapper.updateById(existing);
                    updated++;
                } else {
                    skipped++;
                }
            }
        }
        return new ImportEntityStatVo(created, updated, skipped);
    }

    /** 清空指定目标（不可逆·确认弹窗在前端）。 */
    public PurgeResultVo purge(String target) {
        int deleted;
        switch (target) {
            case "usage-logs" -> deleted = usageLogMapper.delete(null);
            case "request-logs" -> {
                int total = 0;
                int batch;
                do {
                    batch = requestLogStore.deleteBefore(LocalDateTime.now().plusDays(1), REQUEST_LOG_PURGE_BATCH);
                    if (batch > 0) {
                        total += batch;
                    }
                } while (batch >= REQUEST_LOG_PURGE_BATCH);
                deleted = batch < 0 ? -1 : total;
            }
            case "tasks" -> deleted = taskMapper.delete(null);
            case "failover-events" -> deleted = failoverEventMapper.delete(null);
            default -> throw new IllegalArgumentException("未知清空目标: " + target);
        }
        PurgeResultVo result = new PurgeResultVo(deleted, target);
        log.info("手动清空 {} 完成: {}", target, result);
        return result;
    }
}
