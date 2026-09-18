package com.smms.sys.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.security.SecurityUtils;
import com.smms.sys.dto.RolePermRequest;
import com.smms.sys.dto.RoleSaveRequest;
import com.smms.sys.dto.RoleVO;
import com.smms.sys.service.SysRoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色管理接口（FR-SYS-04）。
 */
@RestController
@RequestMapping("/api/v1/roles")
@RequiredArgsConstructor
public class SysRoleController {

    private final SysRoleService roleService;

    @GetMapping
    @RequirePerm("sys:role:list")
    public ApiResponse<List<RoleVO>> list() {
        return ApiResponse.ok(roleService.list());
    }

    @PostMapping
    @RequirePerm("sys:role:create")
    @OpLog(module = "系统管理", action = "新增角色", target = "#req.roleName")
    public ApiResponse<Void> create(@Valid @RequestBody RoleSaveRequest req) {
        roleService.create(req, SecurityUtils.getLoginUser().username());
        return ApiResponse.ok();
    }

    @PutMapping("/{id}")
    @RequirePerm("sys:role:update")
    @OpLog(module = "系统管理", action = "编辑角色", target = "#req.roleName")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody RoleSaveRequest req) {
        roleService.update(id, req, SecurityUtils.getLoginUser().username());
        return ApiResponse.ok();
    }

    @PutMapping("/{id}/status")
    @RequirePerm("sys:role:status")
    @OpLog(module = "系统管理", action = "停用启用角色", target = "#id")
    public ApiResponse<Void> updateStatus(@PathVariable Long id, int status) {
        roleService.updateStatus(id, status, SecurityUtils.getLoginUser().username());
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/permissions")
    @RequirePerm("sys:role:list")
    public ApiResponse<List<Long>> getPerms(@PathVariable Long id) {
        return ApiResponse.ok(roleService.getPermIds(id));
    }

    @PutMapping("/{id}/permissions")
    @RequirePerm("sys:role:update")
    @OpLog(module = "系统管理", action = "分配权限", target = "#id")
    public ApiResponse<Void> assignPerms(@PathVariable Long id,
                                         @Valid @RequestBody RolePermRequest req) {
        roleService.assignPerms(id, req.permIds(), SecurityUtils.getLoginUser().username());
        return ApiResponse.ok();
    }
}
