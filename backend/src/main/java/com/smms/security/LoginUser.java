package com.smms.security;

import java.util.Set;

/**
 * 已登录用户上下文（含角色与权限码集合）。
 */
public record LoginUser(
        Long userId,
        String username,
        String realName,
        java.util.List<String> roleCodes,
        java.util.List<String> roleNames,
        Set<String> permCodes,
        /** 密码指纹：密码修改后旧 Token 立即失效（FR-SYS-03） */
        String pwdFp,
        /** 是否强制下次登录修改密码（BR-SYS-02） */
        boolean mustChangePwd
) {
}
