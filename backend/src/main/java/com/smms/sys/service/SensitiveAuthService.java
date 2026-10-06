package com.smms.sys.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.security.LoginUser;
import com.smms.security.SecurityUtils;
import com.smms.sys.entity.SysUser;
import com.smms.sys.mapper.SysRoleMapper;
import com.smms.sys.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 敏感操作二次密码确认（BR-SYS-06）：审核、授权、参数修改、重置密码等操作
 * 须由当前登录账号再次输入密码确认，防止会话被冒用后执行高风险操作；
 * 授权人身份校验（FR-SALE-07/11、FR-PUR-06）：授权须店长及以上账号输入账号+密码。
 */
@Service
@RequiredArgsConstructor
public class SensitiveAuthService {

    /** 授权角色：店长及以上（FR-SALE-07/11、FR-PUR-06） */
    private static final List<String> AUTH_ROLE_CODES = List.of("SUPER_ADMIN", "MANAGER");

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
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

    /**
     * 授权人身份校验（FR-SALE-07/11、FR-PUR-06）：按账号+密码核对授权人身份，
     * 且授权人须在岗启用并持有店长及以上角色；校验通过返回授权人信息供单据落库。
     * 账号不存在/停用/密码错误统一返回"授权账号或密码错误"，避免账号探测。
     */
    public Authorizer authorize(String username, String rawPassword) {
        if (username == null || username.isBlank() || rawPassword == null || rawPassword.isBlank()) {
            throw new BusinessException(ErrorCode.AUTHORIZER_AUTH_FAILED);
        }
        SysUser user = userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, username.trim()));
        if (user == null || user.getStatus() == null || user.getStatus() != 1
                || !passwordEncoder.matches(rawPassword, user.getPassword())) {
            throw new BusinessException(ErrorCode.AUTHORIZER_AUTH_FAILED);
        }
        boolean manager = roleMapper.selectByUserId(user.getId()).stream()
                .anyMatch(role -> role.getStatus() != null && role.getStatus() == 1
                        && AUTH_ROLE_CODES.contains(role.getRoleCode()));
        if (!manager) {
            throw new BusinessException(ErrorCode.AUTHORIZER_NOT_MANAGER);
        }
        return new Authorizer(user.getId(), user.getUsername(), user.getRealName());
    }

    /** 授权人（已通过账号+密码+角色校验） */
    public record Authorizer(Long userId, String username, String realName) {
    }
}
