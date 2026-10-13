package com.smms.sys.dto;

import com.smms.sys.entity.SysBackupRecord;

import java.time.LocalDateTime;

/** 数据备份记录视图（FR-SYS-10） */
public record SysBackupRecordVO(
        Long id,
        String fileName,
        String filePath,
        Long fileSize,
        /** SCHEDULED 定时 / MANUAL 手工一键 */
        String triggerType,
        String triggerBy,
        /** SUCCESS / FAILED */
        String status,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        Long durationMs,
        String errorMessage,
        LocalDateTime createTime) {

    public static SysBackupRecordVO of(SysBackupRecord record) {
        return new SysBackupRecordVO(record.getId(), record.getFileName(), record.getFilePath(),
                record.getFileSize(), record.getTriggerType(), record.getTriggerBy(), record.getStatus(),
                record.getStartedAt(), record.getFinishedAt(), record.getDurationMs(),
                record.getErrorMessage(), record.getCreateTime());
    }
}
