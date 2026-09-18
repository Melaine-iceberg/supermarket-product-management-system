package com.smms.sys.controller;

import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.sys.dto.MenuNode;
import com.smms.sys.service.SysPermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限接口（FR-SYS-05）：完整权限树（角色分配界面用）。
 */
@RestController
@RequestMapping("/api/v1/permissions")
@RequiredArgsConstructor
public class SysPermissionController {

    private final SysPermissionService permissionService;

    @GetMapping("/tree")
    @RequirePerm("sys:role:list")
    public ApiResponse<List<MenuNode>> tree() {
        return ApiResponse.ok(permissionService.getFullTree());
    }
}
