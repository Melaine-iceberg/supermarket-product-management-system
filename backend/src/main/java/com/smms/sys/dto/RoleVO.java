package com.smms.sys.dto;

import java.time.LocalDateTime;

/**
 * 角色列表/详情视图（FR-SYS-04）。
 */
public record RoleVO(
        Long id,
        String roleName,
        String roleCode,
        String description,
        boolean builtin,
        Integer status,
        LocalDateTime createTime
) {
}
