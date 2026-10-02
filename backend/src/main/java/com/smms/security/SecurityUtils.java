package com.smms.security;

import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

/**
 * 当前登录用户工具。
 */
public final class SecurityUtils {

    /** 完整会员手机号可见角色（BR-MEM-08：店长及以上） */
    private static final List<String> FULL_PHONE_ROLES = List.of("SUPER_ADMIN", "MANAGER");

    /** 供应商敏感字段可见角色（BR-SUP-01：银行账号与纳税人识别号，店长及以上） */
    private static final List<String> SUPPLIER_SENSITIVE_ROLES = List.of("SUPER_ADMIN", "MANAGER");

    private SecurityUtils() {
    }

    /** 获取当前登录用户，未登录抛 401 业务异常 */
    public static LoginUser getLoginUser() {
        LoginUser user = getLoginUserOrNull();
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    public static LoginUser getLoginUserOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LoginUser user) {
            return user;
        }
        return null;
    }

    /** 当前登录用户是否可查看完整会员手机号（BR-MEM-08：店长及以上；其余角色脱敏 138****5678） */
    public static boolean canViewMemberPhone() {
        LoginUser user = getLoginUserOrNull();
        return user != null && user.roleCodes() != null
                && user.roleCodes().stream().anyMatch(FULL_PHONE_ROLES::contains);
    }

    /** 当前登录用户是否可查看供应商银行账号与纳税人识别号（BR-SUP-01：店长及以上；其余角色账号脱敏、识别号不返回） */
    public static boolean canViewSupplierSensitive() {
        LoginUser user = getLoginUserOrNull();
        return user != null && user.roleCodes() != null
                && user.roleCodes().stream().anyMatch(SUPPLIER_SENSITIVE_ROLES::contains);
    }
}
