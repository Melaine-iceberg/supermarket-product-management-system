package com.smms.sys.dto;

import java.util.List;
import java.util.Set;

/**
 * 当前登录用户信息（/auth/me、/auth/login 返回）。
 */
public record UserInfo(
        Long id,
        String username,
        String realName,
        List<String> roleCodes,
        List<String> roleNames,
        Set<String> permCodes
) {
}
