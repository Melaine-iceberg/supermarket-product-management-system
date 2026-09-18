package com.smms.sys.dto;

import java.util.List;

/**
 * 当前用户信息响应（GET /auth/me）。
 */
public record MeResponse(
        boolean mustChangePwd,
        UserInfo user,
        List<MenuNode> menus
) {
}
