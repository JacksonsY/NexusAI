package com.nexusai.control.domain.platform.module.catalog;

import com.nexusai.control.domain.platform.system.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 功能模块目录（静态清单类）：14 个模块定义 + 各模块参数 schema + 装配可用性判定。
 * 只描述「有哪些模块、长什么样」，不做读写编排——开关/参数的读写在 {@code ModuleService}。
 *
 * @author nexusai
 */
@Component
@RequiredArgsConstructor
public class ModuleCatalog {

    private final Environment env;
    private final SystemConfigService systemConfig;

    /** 模块定义（name/显示名/描述/分类/装配默认/管理路由/默认顺序）。 */
    public record ModuleDef(String name, String displayName, String description, String category,
                            boolean defaultEnabled, String adminRoute, int order) {
    }

    /**
     * 模块参数声明（schema 驱动设置抽屉）：hot=true 写 KV 后 5s 热生效（组件经 RuntimeSettings 热读）；
     * hot=false 为启动配置只读展示（不做假开关）。KV 键 = module.{module}.{key}。
     */
    public record ParamDef(String key, String label, String type, Object defaultValue,
                           String unit, String hint, boolean hot, Map<String, Object> extra) {
        static ParamDef number(String key, String label, Object def, String unit, String hint, boolean hot,
                               long min, long max) {
            return new ParamDef(key, label, "number", def, unit, hint, hot, Map.of("min", min, "max", max));
        }

        static ParamDef bool(String key, String label, Object def, String hint, boolean hot) {
            return new ParamDef(key, label, "bool", def, null, hint, hot, Map.of());
        }

        static ParamDef readonly(String key, String label, Object value, String hint) {
            return new ParamDef(key, label, "readonly", value, null, hint, false, Map.of());
        }

        static ParamDef string(String key, String label, Object def, String hint) {
            return new ParamDef(key, label, "string", def, null, hint, true, Map.of());
        }

        /** 敏感凭证（AES-256-GCM 加密存 KV·回显仅掩码） */
        static ParamDef secret(String key, String label, String hint) {
            return new ParamDef(key, label, "secret", null, null, hint, true, Map.of());
        }
    }

    public List<ParamDef> paramsOf(String name) {
        return switch (name) {
            case "response_cache" -> List.of(
                    ParamDef.number("ttl_seconds", "缓存 TTL", envLong("nexusai.cache.response.ttl-seconds", 300),
                            "秒", "精确匹配响应缓存的存活时间（Redis 与磁盘指针同 TTL）", true, 1, 86400),
                    ParamDef.number("disk_threshold_kb", "落盘阈值",
                            envLong("nexusai.cache.response.disk-threshold-bytes", 262144) / 1024,
                            "KB", "超过此大小的响应落盘存指针，防大体撑爆 Redis", true, 1, 102400),
                    ParamDef.readonly("nexusai.cache.response.enabled", "装配开关",
                            env.getProperty("nexusai.cache.response.enabled", "false"),
                            "启动配置（application.yml）·重启生效"));
            case "anthropic_auto_cache" -> List.of(
                    ParamDef.number("block_window", "窗口锚定块数", 20,
                            "块", "可缓存内容块数达到该阈值时在窗口边界补打第二个断点", true, 4, 200));
            case "sticky_affinity" -> List.of(
                    ParamDef.readonly("nexusai.pool.sticky.ttl-seconds", "粘性 TTL（秒）",
                            env.getProperty("nexusai.pool.sticky.ttl-seconds", "300"),
                            "启动配置（Caffeine 过期构造期固定）·重启生效"),
                    ParamDef.readonly("nexusai.pool.sticky.store", "存储模式",
                            env.getProperty("nexusai.pool.sticky.store", "memory"),
                            "memory=单实例本地 / redis=多实例共享·重启生效"));
            case "disk_cache" -> List.of(
                    ParamDef.number("max_mb", "容量上限",
                            envLong("nexusai.cache.disk.max-bytes", 1073741824L) / 1024 / 1024,
                            "MB", "磁盘缓存总容量闸，超限拒写（放弃缓存不降级）", true, 16, 102400),
                    ParamDef.readonly("nexusai.cache.disk.dir", "缓存目录",
                            env.getProperty("nexusai.cache.disk.dir", "{java.io.tmpdir}/nexusai-disk-cache"),
                            "启动配置·重启生效"));
            case "idempotency" -> List.of(
                    ParamDef.number("ttl_seconds", "幂等键 TTL", envLong("nexusai.idempotent.ttl-seconds", 600),
                            "秒", "Idempotency-Key 显式幂等的去重窗口", true, 10, 86400),
                    ParamDef.number("auto_dedup_seconds", "自动去重窗口",
                            envLong("nexusai.idempotent.auto-dedup-seconds", 0),
                            "秒", "无幂等头时按 body-hash 短窗去重，挡网络重发双扣；0=关闭", true, 0, 3600));
            case "chat_pii_redaction" -> List.of(
                    ParamDef.bool("redact_email", "脱敏邮箱", true, "user@example.com → [REDACTED_EMAIL]", true),
                    ParamDef.bool("redact_phone", "脱敏手机号", true, "中国大陆 11 位手机号", true),
                    ParamDef.bool("redact_id_card", "脱敏身份证", true, "18 位身份证号（含末位 X）", true),
                    ParamDef.bool("redact_bank_card", "脱敏银行卡", false,
                            "13-19 位纯数字·误伤面大（订单号/时间戳）按需开启", true));
            case "model_directives" -> List.of(
                    ParamDef.readonly("separator", "指令分隔符", "#",
                            "模型名后缀格式：model#key=value,key2=value2·数字/布尔自动判型"));
            case "server_chan_push" -> List.of(
                    ParamDef.secret("send_key", "SendKey", "sct.ftqq.com 申请的推送密钥（AES 加密存储）"));
            case "bark_push" -> List.of(
                    ParamDef.secret("device_key", "Device Key", "Bark App 内的设备密钥（AES 加密存储）"),
                    ParamDef.string("server", "Bark 服务器", "https://api.day.app", "自建 Bark 服务时修改"));
            case "config_backup" -> List.of(
                    ParamDef.string("dir", "备份目录", "", "留空 = ~/.nexusai/backups"),
                    ParamDef.number("keep_count", "保留份数", 14, "份", "超出份数滚动删除最旧备份", true, 1, 365));
            case "async_tasks" -> List.of(
                    ParamDef.readonly("task_retention_days", "任务保留天数",
                            systemConfig.getInt("task_retention_days", 30),
                            "在系统设置「记录清理策略」中调整"));
            default -> List.of();
        };
    }

    public List<ModuleDef> defs() {
        boolean responseCacheWired = "true".equalsIgnoreCase(env.getProperty("nexusai.cache.response.enabled", "false"));
        return List.of(
                new ModuleDef("response_cache", "响应缓存",
                        "精确匹配响应缓存（Redis+磁盘两级）：幂等/确定性请求命中省上游调用与额度（USD）",
                        "performance", responseCacheWired, "/cache-monitor", 10),
                new ModuleDef("anthropic_auto_cache", "Anthropic 缓存注入",
                        "发往 Anthropic 协议渠道的请求自动规划 cache_control 断点（≤4），提升提示缓存命中",
                        "performance", "true".equalsIgnoreCase(env.getProperty("nexusai.cache.anthropic-auto-cache.enabled", "true")),
                        "/cache-monitor", 20),
                new ModuleDef("sticky_affinity", "会话亲和",
                        "同会话粘住同渠道 Key（metadata/摘要链/token 三级亲和键），最大化上游提示缓存命中",
                        "performance", true, "/cache-monitor", 30),
                new ModuleDef("disk_cache", "磁盘缓存",
                        "大响应（>阈值）落盘、Redis 只存指针，防大体撑爆 Redis",
                        "performance", true, "/cache-monitor", 40),
                new ModuleDef("idempotency", "幂等去重",
                        "Idempotency-Key 显式幂等 + body-hash 自动去重窗口，防网络重发双扣",
                        "security", true, null, 50),
                new ModuleDef("async_tasks", "异步任务",
                        "图像/视频生成等长任务的提交-轮询通道（冻结退费 + TaskPoller 进程内调度）",
                        "integration", true, "/async-tasks", 60),
                new ModuleDef("chat_pii_redaction", "敏感信息保护",
                        "出站前对请求体做 PII 脱敏（邮箱/手机号/身份证/银行卡 → 占位符），上游看不到原文",
                        "security", false, null, 70),
                new ModuleDef("model_directives", "模型后缀参数",
                        "模型名 # 后缀指令解析为请求参数（gpt-4o#temperature=0.7,top_p=0.9），请求体显式参数优先",
                        "integration", false, null, 80),
                new ModuleDef("management_tokens", "访问令牌",
                        "控制面 M2M 项目令牌（nai_project）：外部系统经项目令牌调用管理 API",
                        "security", true, "/projects", 90),
                new ModuleDef("proxy_nodes", "代理节点",
                        "出口代理池：上游透传按渠道/全局绑定 HTTP/SOCKS5 代理出站，探测任务维护在线状态",
                        "integration", true, "/proxy-nodes", 100),
                new ModuleDef("important_notification", "通知服务",
                        "重要事件（代理节点离线/恢复等）推送给运维者，经下方启用的推送通道送达",
                        "integration", false, null, 110),
                new ModuleDef("server_chan_push", "Server 酱推送",
                        "通知服务的 Server 酱（ftqq）通道：配置 SendKey 后微信收通知",
                        "integration", false, null, 120),
                new ModuleDef("bark_push", "Bark 推送",
                        "通知服务的 Bark 通道：配置 Device Key 后 iOS 收推送",
                        "integration", false, null, 130),
                new ModuleDef("config_backup", "配置备份",
                        "每日 04:10 全量导出配置 JSON 到备份目录并按保留份数滚动（S3/OSS 留 P2）",
                        "integration", false, null, 140)
        );
    }

    public ModuleDef require(String name) {
        return defs().stream().filter(d -> d.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知模块: " + name));
    }

    public boolean known(String name) {
        return defs().stream().anyMatch(d -> d.name().equals(name));
    }

    /** 装配条件是否满足（available 维度·与 KV enabled 开关正交）。 */
    public boolean available(ModuleDef def) {
        if ("response_cache".equals(def.name())) {
            // 响应缓存 bean 由 nexusai.cache.response.enabled 装配（启动期条件）
            return "true".equalsIgnoreCase(env.getProperty("nexusai.cache.response.enabled", "false"));
        }
        return true;
    }

    public String unavailableHint(ModuleDef def) {
        if ("response_cache".equals(def.name())) {
            return "需启动配置 nexusai.cache.response.enabled=true 装配";
        }
        return "装配条件不满足";
    }

    private long envLong(String key, long def) {
        try {
            return Long.parseLong(env.getProperty(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
