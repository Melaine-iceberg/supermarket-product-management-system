package com.smms.sys.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.common.api.ErrorCode;
import com.smms.common.api.PageVO;
import com.smms.common.exception.BusinessException;
import com.smms.security.SecurityUtils;
import com.smms.sys.dto.SysBackupRecordVO;
import com.smms.sys.entity.SysBackupRecord;
import com.smms.sys.mapper.SysBackupRecordMapper;
import com.smms.sys.service.DatabaseBackupService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据备份接口（FR-SYS-10 / GAP-P2-23）：
 * 备份记录查看（含定时备份与手工一键）；一键触发全量备份（仅超级管理员，操作留痕）。
 */
@RestController
@RequestMapping("/api/v1/system/backups")
@RequiredArgsConstructor
public class SystemBackupController {

    private final SysBackupRecordMapper backupMapper;
    private final DatabaseBackupService backupService;

    /** 备份记录分页（倒序）：文件名/大小/触发方式/结果/耗时/操作人/失败原因 */
    @GetMapping
    @RequirePerm("sys:backup:list")
    public ApiResponse<PageVO<SysBackupRecordVO>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<SysBackupRecord> p = new Page<>(Math.max(page, 1), size <= 0 ? 20 : Math.min(size, 100));
        Page<SysBackupRecord> result = backupMapper.selectPage(p, Wrappers.<SysBackupRecord>lambdaQuery()
                .orderByDesc(SysBackupRecord::getStartedAt)
                .orderByDesc(SysBackupRecord::getId));
        return ApiResponse.ok(new PageVO<>(result.getRecords().stream().map(SysBackupRecordVO::of).toList(),
                result.getTotal()));
    }

    /** 一键触发全量备份（FR-SYS-10）：仅超级管理员；文件保留策略与定时备份一致 */
    @PostMapping
    @RequirePerm("sys:backup:run")
    @OpLog(module = "系统管理", action = "一键触发数据备份")
    public ApiResponse<SysBackupRecordVO> trigger() {
        if (!SecurityUtils.isSuperAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "数据备份仅超级管理员可执行（FR-SYS-10）");
        }
        return ApiResponse.ok(backupService.backup("MANUAL", SecurityUtils.getLoginUser().username()));
    }
}
