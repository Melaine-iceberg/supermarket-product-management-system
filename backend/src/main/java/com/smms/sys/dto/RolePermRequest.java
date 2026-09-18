package com.smms.sys.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 角色权限分配请求（FR-SYS-05）：全量勾选的权限 ID 列表。
 */
public record RolePermRequest(
        @NotNull(message = "权限列表不能为空") List<Long> permIds
) {
}
