package com.smms.sys.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.PageVO;
import com.smms.sys.entity.LoginLog;
import com.smms.sys.mapper.LoginLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 登录日志服务（FR-SYS-01）：记录成功/失败、IP、浏览器、操作系统、时间；只增不改不删。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginLogService {

    private final LoginLogMapper loginLogMapper;

    @Async("logExecutor")
    public void recordAsync(LoginLog entity) {
        try {
            if (entity.getLoginTime() == null) {
                entity.setLoginTime(LocalDateTime.now());
            }
            loginLogMapper.insert(entity);
        } catch (Exception e) {
            log.error("登录日志写入失败: username={}", entity.getUsername(), e);
        }
    }

    /** 分页查询：按用户名/结果/时间过滤 */
    public PageVO<LoginLog> page(String username, Integer status,
                                 LocalDate beginDate, LocalDate endDate, int page, int size) {
        Page<LoginLog> pager = loginLogMapper.selectPage(
                Page.of(page, size),
                Wrappers.<LoginLog>lambdaQuery()
                        .like(username != null && !username.isBlank(), LoginLog::getUsername, username)
                        .eq(status != null, LoginLog::getStatus, status)
                        .ge(beginDate != null, LoginLog::getLoginTime, beginDate == null ? null : beginDate.atStartOfDay())
                        .lt(endDate != null, LoginLog::getLoginTime, endDate == null ? null : endDate.plusDays(1).atStartOfDay())
                        .orderByDesc(LoginLog::getId));
        return PageVO.of(pager);
    }
}
