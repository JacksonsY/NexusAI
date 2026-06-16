package com.nexusai.control.domain.platform.system.job;

import com.nexusai.common.util.JsonUtils;
import com.nexusai.control.domain.platform.system.service.SystemMaintenanceService;
import com.nexusai.runtime.ModuleToggles;
import com.nexusai.runtime.RuntimeSettings;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import com.nexusai.control.domain.platform.notify.service.NotificationService;

/**
 * 配置自动备份（MVP 本地目录·S3/OSS 留 P2）：
 * 模块开关 {@code module.config_backup.enabled}（默认关）；每日 04:10 全量导出配置 JSON
 * 到备份目录并按保留份数滚动清理。进程内守护线程 + Redisson leader 锁（不引入重型调度器）。
 *
 * @author nexusai
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigBackupJob {

    private static final String LEADER_LOCK = "nai:lock:config-backup";

    /** 守护线程轮询间隔（ms·每分钟检查是否到点） */
    private static final long POLL_INTERVAL_MS = 60_000L;
    /** leader 锁租约（秒·一轮备份上限） */
    private static final long LOCK_LEASE_SECONDS = 300;
    /** 每日备份触发窗口（04:10~04:20·容守护线程分钟级抖动） */
    private static final int BACKUP_HOUR = 4;
    private static final int BACKUP_MINUTE_FROM = 10;
    private static final int BACKUP_MINUTE_TO = 20;
    /** 备份文件名前缀（滚动清理与计数按此识别） */
    private static final String BACKUP_FILE_PREFIX = "nexusai-config-";

    private final SystemMaintenanceService maintenance;
    private final ModuleToggles toggles;
    private final RuntimeSettings settings;
    private final RedissonClient redisson;
    private final ObjectProvider<NotificationService> notificationProvider;

    private volatile boolean running = true;
    private volatile LocalDate lastRunDate;
    private Thread daemon;

    @PostConstruct
    public void start() {
        daemon = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                    tick();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    log.warn("配置备份轮异常: {}", e.toString());
                }
            }
        }, "nai-config-backup");
        daemon.setDaemon(true);
        daemon.start();
    }

    void tick() {
        if (!toggles.enabled("config_backup", false)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.getHour() != BACKUP_HOUR || now.getMinute() < BACKUP_MINUTE_FROM || now.getMinute() > BACKUP_MINUTE_TO
                || now.toLocalDate().equals(lastRunDate)) {
            return;
        }
        RLock lock = redisson.getLock(LEADER_LOCK);
        boolean acquired;
        try {
            acquired = lock.tryLock(0, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        lastRunDate = now.toLocalDate();
        if (!acquired) {
            return;
        }
        backupOnce();
    }

    /** 执行一次备份（测试端点也走此入口）。 */
    public Path backupOnce() {
        Path dir = backupDir();
        try {
            Files.createDirectories(dir);
            String name = BACKUP_FILE_PREFIX + LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json";
            Path file = dir.resolve(name);
            Files.writeString(file, JsonUtils.toJson(maintenance.exportConfig()));
            rotate(dir);
            log.info("配置备份完成: {}", file);
            return file;
        } catch (IOException e) {
            log.warn("配置备份失败: {}", e.toString());
            NotificationService svc = notificationProvider.getIfAvailable();
            if (svc != null) {
                svc.notify("config_backup_failed", "配置备份失败", e.toString());
            }
            return null;
        }
    }

    /** 按保留份数滚动删除最旧备份。 */
    private void rotate(Path dir) throws IOException {
        int keep = Math.max(1, settings.getInt("module.config_backup.keep_count", 14));
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> backups = files
                    .filter(p -> p.getFileName().toString().startsWith(BACKUP_FILE_PREFIX))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            for (int i = 0; i < backups.size() - keep; i++) {
                Files.deleteIfExists(backups.get(i));
            }
        }
    }

    public Path backupDir() {
        String configured = settings.getString("module.config_backup.dir", "");
        return configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".nexusai", "backups")
                : Path.of(configured);
    }

    /** 现存备份份数（模块抽屉指标）。 */
    public long backupCount() {
        try (Stream<Path> files = Files.list(backupDir())) {
            return files.filter(p -> p.getFileName().toString().startsWith(BACKUP_FILE_PREFIX)).count();
        } catch (IOException e) {
            // 目录不存在/不可读视为 0 份（指标用途·非关键路径）；记 debug 便于排查
            log.debug("统计备份份数失败（视为 0）: {}", e.toString());
            return 0;
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (daemon != null) {
            daemon.interrupt();
        }
    }
}
