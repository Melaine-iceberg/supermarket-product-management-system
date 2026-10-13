package com.smms.common.sched.tasks;

import com.smms.common.sched.ScheduledTask;
import com.smms.sys.dto.SysBackupRecordVO;
import com.smms.sys.service.DatabaseBackupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 每日数据备份（FR-SYS-10 / GAP-P2-23）：营业日结束后（默认 23:30）全量备份，保留最近 30 份。
 * 失败时抛出异常，由任务框架重试并记 FAILED（每次尝试均落 sys_backup_record 可查）。
 */
@Component
@RequiredArgsConstructor
public class DatabaseBackupTask implements ScheduledTask {

    private final DatabaseBackupService backupService;

    @Override
    public String code() {
        return "DATABASE_BACKUP";
    }

    @Override
    public String name() {
        return "每日数据备份";
    }

    @Override
    public String description() {
        return "mysqldump 全量备份（含存储过程/触发器），保留最近 30 份；重复执行生成新备份文件，可手工补跑";
    }

    @Override
    public String cron() {
        return "0 30 23 * * ?";
    }

    @Override
    public String execute() {
        SysBackupRecordVO record = backupService.backup("SCHEDULED", null);
        if (!"SUCCESS".equals(record.status())) {
            throw new IllegalStateException("备份失败：" + record.errorMessage());
        }
        return "备份成功：" + record.fileName() + "（" + humanSize(record.fileSize()) + "）";
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format("%.1f GB", bytes / 1024.0 / 1024 / 1024);
        }
        if (bytes >= 1024L * 1024) {
            return String.format("%.1f MB", bytes / 1024.0 / 1024);
        }
        if (bytes >= 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }
}
