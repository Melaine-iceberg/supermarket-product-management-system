package com.smms.sys.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.common.api.PageVO;
import com.smms.security.SecurityUtils;
import com.smms.sys.dto.UserCreateRequest;
import com.smms.sys.dto.UserStatusRequest;
import com.smms.sys.dto.UserUpdateRequest;
import com.smms.sys.dto.UserVO;
import com.smms.sys.service.SysUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理接口（FR-SYS-02）：用户不可物理删除，只可停用。
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class SysUserController {

    private final SysUserService userService;

    @GetMapping
    @RequirePerm("sys:user:list")
    public ApiResponse<PageVO<UserVO>> page(@RequestParam(required = false) String username,
                                            @RequestParam(required = false) Integer status,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(userService.page(username, status, page, Math.min(size, 500)));
    }

    @PostMapping
    @RequirePerm("sys:user:create")
    @OpLog(module = "系统管理", action = "新增用户", target = "#req.username")
    public ApiResponse<SysUserService.UserCreated> create(@Valid @RequestBody UserCreateRequest req) {
        return ApiResponse.ok(userService.create(req, SecurityUtils.getLoginUser().username()));
    }

    @PutMapping("/{id}")
    @RequirePerm("sys:user:update")
    @OpLog(module = "系统管理", action = "编辑用户", target = "#id")
    public ApiResponse<Void> update(@PathVariable Long id,
                                    @Valid @RequestBody UserUpdateRequest req) {
        userService.update(id, req, SecurityUtils.getLoginUser().username());
        return ApiResponse.ok();
    }

    @PutMapping("/{id}/status")
    @RequirePerm("sys:user:status")
    @OpLog(module = "系统管理", action = "停用启用用户", target = "#id")
    public ApiResponse<Void> updateStatus(@PathVariable Long id,
                                          @Valid @RequestBody UserStatusRequest req) {
        userService.updateStatus(id, req.status(), SecurityUtils.getLoginUser().username());
        return ApiResponse.ok();
    }

    @PutMapping("/{id}/reset-password")
    @RequirePerm("sys:user:reset-password")
    @OpLog(module = "系统管理", action = "重置密码", target = "#id")
    public ApiResponse<String> resetPassword(@PathVariable Long id) {
        return ApiResponse.ok(userService.resetPassword(id, SecurityUtils.getLoginUser().username()));
    }
}
