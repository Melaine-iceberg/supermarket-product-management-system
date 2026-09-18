package com.smms.sys.dto;

import java.util.List;

/**
 * 登录响应（FR-SYS-01、FR-SYS-05）：Token + 用户信息 + 动态菜单 + 权限码。
 */
public record LoginResponse(
        String token,
        long expiresInSeconds,
        boolean mustChangePwd,
        UserInfo user,
        List<MenuNode> menus
) {
}
