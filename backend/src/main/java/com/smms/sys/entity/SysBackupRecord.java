package com.smms.sys.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据备份记录（FR-SYS-10）：每日定时全量备份与手工一键备份的执行记录。
 */
@Data
@TableName("sys_backup_record")
public class SysBackupRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String fileName;
    private String filePath;
    private Long fileSize;
    /** SCHEDULED 定时 / MANUAL 手工一键 */
    private String triggerType;
    private String triggerBy;
    /** SUCCESS / FAILED */
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private String errorMessage;
    private LocalDateTime createTime;
}
