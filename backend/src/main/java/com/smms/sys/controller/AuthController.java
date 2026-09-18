package com.smms.sys.controller;

import com.smms.common.api.ApiResponse;
import com.smms.sys.dto.ChangePasswordRequest;
import com.smms.sys.dto.LoginRequest;
import com.smms.sys.dto.LoginResponse;
import com.smms.sys.dto.MeResponse;
import com.smms.sys.service.AuthService;
import com.smms.sys.service.SysConfigService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（FR-SYS-01、FR-SYS-03）。
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final SysConfigService configService;

    /** 用户名 + 密码登录（公开） */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletRequest httpRequest) {
        return ApiResponse.ok(authService.login(request, httpRequest));
    }

    /** 登录页展示的超市名称（公开，IR-UI-08） */
    @GetMapping("/store-name")
    public ApiResponse<String> storeName() {
        return ApiResponse.ok(configService.getStoreName());
    }

    /** 登出：当前 Token 加入黑名单 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        authService.logout();
        return ApiResponse.ok();
    }

    /** 当前登录用户信息 + 动态菜单（FR-SYS-05） */
    @GetMapping("/me")
    public ApiResponse<MeResponse> me() {
        return ApiResponse.ok(authService.me());
    }

    /** 修改自身密码（FR-SYS-03） */
    @PutMapping("/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request.oldPassword(), request.newPassword());
        return ApiResponse.ok();
    }
}
