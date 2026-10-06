package com.smms.sys.service;

import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.security.LoginUser;
import com.smms.security.SecurityUtils;
import com.smms.sys.entity.SysUser;
import com.smms.sys.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 敏感操作二次密码确认（BR-SYS-06）：审核、授权、参数修改、重置密码等操作
 * 须由当前登录账号再次输入密码确认，防止会话被冒用后执行高风险操作。
 */
@Service
@RequiredArgsConstructor
public class SensitiveAuthService {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    /**
     * 校验当前登录用户输入的密码：为空拒绝；与数据库密码哈希不匹配拒绝。
     * 校验失败的操作一律未执行（调用方在业务处理前调用）。
     */
    public void confirmPassword(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new BusinessException(ErrorCode.SENSITIVE_PWD_REQUIRED);
        }
        LoginUser loginUser = SecurityUtils.getLoginUser();
        SysUser user = userMapper.selectById(loginUser.userId());
        if (user == null || !passwordEncoder.matches(rawPassword, user.getPassword())) {
            throw new BusinessException(ErrorCode.SENSITIVE_PWD_ERROR);
        }
    }
}
