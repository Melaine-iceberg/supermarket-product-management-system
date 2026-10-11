package com.smms.sys.controller;

import com.smms.common.api.ApiResponse;
import com.smms.sys.dto.ChangePasswordRequest;
import com.smms.sys.dto.CodeLoginRequest;
import com.smms.sys.dto.LoginMethodsVO;
import com.smms.sys.dto.LoginRequest;
import com.smms.sys.dto.LoginResponse;
import com.smms.sys.dto.MeResponse;
import com.smms.sys.dto.RegisterRequest;
import com.smms.sys.dto.SendCodeRequest;
import com.smms.sys.dto.WechatLoginRequest;
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

import java.util.Map;

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

    /** 当前开放的登录方式（公开，V1.2）：登录页据此渲染 Tab，与服务端开关保持一致 */
    @GetMapping("/login-methods")
    public ApiResponse<LoginMethodsVO> loginMethods() {
        return ApiResponse.ok(authService.loginMethods());
    }

    /** 发送短信/邮件验证码（公开，V1.2） */
    @PostMapping("/code")
    public ApiResponse<Map<String, Object>> sendCode(@Valid @RequestBody SendCodeRequest request,
                                                    HttpServletRequest httpRequest) {
        return ApiResponse.ok(authService.sendCode(request, httpRequest));
    }

    /** 手机号/邮箱 + 验证码登录（公开，V1.2） */
    @PostMapping("/login-by-code")
    public ApiResponse<LoginResponse> loginByCode(@Valid @RequestBody CodeLoginRequest request,
                                                  HttpServletRequest httpRequest) {
        return ApiResponse.ok(authService.loginByCode(request, httpRequest));
    }

    /** 获取微信扫码地址（公开，V1.2） */
    @GetMapping("/wechat/auth-url")
    public ApiResponse<Map<String, String>> wechatAuthUrl() {
        return ApiResponse.ok(authService.wechatAuthUrl());
    }

    /** 微信扫码回调换取登录态（公开，V1.2） */
    @PostMapping("/wechat/login")
    public ApiResponse<LoginResponse> wechatLogin(@Valid @RequestBody WechatLoginRequest request,
                                                  HttpServletRequest httpRequest) {
        return ApiResponse.ok(authService.loginByWechat(request, httpRequest));
    }

    /** 员工自助注册，注册即用（公开，V1.2） */
    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(@Valid @RequestBody RegisterRequest request,
                                               HttpServletRequest httpRequest) {
        return ApiResponse.ok(authService.register(request, httpRequest));
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
