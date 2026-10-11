package com.smms.sys.service;

import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.security.JwtTokenService;
import com.smms.security.LoginRateLimiter;
import com.smms.security.LoginUser;
import com.smms.security.UserContextService;
import com.smms.sys.dto.LoginRequest;
import com.smms.sys.dto.LoginResponse;
import com.smms.sys.config.LoginProperties;
import com.smms.sys.entity.SysUser;
import com.smms.sys.mapper.SysRoleMapper;
import com.smms.sys.mapper.SysTokenBlacklistMapper;
import com.smms.sys.mapper.SysUserMapper;
import com.smms.sys.mapper.SysUserRoleMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthService 登录分支矩阵单元测试（FR-SYS-01，AC-D03 覆盖率补充）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    @Mock
    private SysUserMapper userMapper;
    @Mock
    private SysUserRoleMapper userRoleMapper;
    @Mock
    private SysRoleMapper roleMapper;
    @Mock
    private SysTokenBlacklistMapper blacklistMapper;
    @Mock
    private JwtTokenService jwtTokenService;
    @Mock
    private UserContextService userContextService;
    @Mock
    private SysConfigService configService;
    @Mock
    private SysPermissionService permissionService;
    @Mock
    private LoginLogService loginLogService;
    @Mock
    private OperationLogService operationLogService;
    @Mock
    private LoginRateLimiter loginRateLimiter;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuthCodeService authCodeService;
    @Mock
    private WechatAuthService wechatAuthService;
    @Mock
    private HttpServletRequest request;

    /** 真实配置对象：登录测试只走密码分支，用默认值即可 */
    private final LoginProperties loginProperties = new LoginProperties();

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userMapper, userRoleMapper, roleMapper, blacklistMapper, jwtTokenService,
                userContextService, configService, permissionService, loginLogService, operationLogService,
                loginRateLimiter, passwordEncoder, authCodeService, wechatAuthService, loginProperties);
        when(loginRateLimiter.tryAcquire(anyString())).thenReturn(true);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
    }

    private SysUser user(int status, LocalDateTime lockUntil) {
        SysUser u = new SysUser();
        u.setId(1L);
        u.setUsername("admin");
        u.setRealName("系统管理员");
        u.setPassword("$2b$10$hash");
        u.setStatus(status);
        u.setFailCount(0);
        u.setLockUntil(lockUntil);
        return u;
    }

    private void stubLoginSuccess(SysUser u, int configExpireHours) {
        when(userMapper.selectOne(any())).thenReturn(u);
        when(passwordEncoder.matches("Admin@2026", u.getPassword())).thenReturn(true);
        when(configService.getInt("token_expire_hours", 8)).thenReturn(configExpireHours);
        when(jwtTokenService.createToken(any(LoginUser.class), anyLong())).thenReturn(
                new JwtTokenService.IssuedToken("jwt.token", "jti-1", Instant.now().plusSeconds(3600)));
        LoginUser loginUser = new LoginUser(1L, "admin", "系统管理员", List.of("ADMIN"),
                List.of("管理员"), Set.of("sys:user:list"), "fp", false);
        when(userContextService.getLoginUser(eq(1L), isNull())).thenReturn(loginUser);
        when(permissionService.getMenuTreeByUserId(1L)).thenReturn(List.of());
    }

    @Test
    @DisplayName("登录成功：默认 8 小时有效期，清零失败计数并记录最后登录时间")
    void login_success_defaultExpire() {
        SysUser u = user(1, null);
        u.setFailCount(3);
        stubLoginSuccess(u, 8);

        LoginResponse resp = authService.login(new LoginRequest("admin", "Admin@2026", false), request);

        assertThat(resp.token()).isEqualTo("jwt.token");
        assertThat(resp.expiresInSeconds()).isEqualTo(8 * 3600L);
        assertThat(resp.user().username()).isEqualTo("admin");
        assertThat(resp.mustChangePwd()).isFalse();
        assertThat(u.getFailCount()).isZero();
        assertThat(u.getLockUntil()).isNull();
        assertThat(u.getLastLoginTime()).isNotNull();
        verify(userMapper).updateById(u);
        verify(userContextService).invalidate(1L);
        verify(loginLogService).recordAsync(any());
        verify(operationLogService).recordAsync(any());
    }

    @Test
    @DisplayName("登录成功：记住我有效期延长至 72 小时")
    void login_success_rememberMe() {
        stubLoginSuccess(user(1, null), 8);

        LoginResponse resp = authService.login(new LoginRequest("admin", "Admin@2026", true), request);

        assertThat(resp.expiresInSeconds()).isEqualTo(72 * 3600L);
    }

    @Test
    @DisplayName("登录失败：用户不存在返回 1101，不校验密码")
    void login_userNotFound() {
        when(userMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody", "x", false), request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.USERNAME_PASSWORD_ERROR.getCode());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("登录失败：账号停用返回 1103")
    void login_accountDisabled() {
        when(userMapper.selectOne(any())).thenReturn(user(0, null));

        assertThatThrownBy(() -> authService.login(new LoginRequest("admin", "x", false), request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.ACCOUNT_DISABLED.getCode());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("登录失败：锁定期内返回 1102 并提示剩余分钟")
    void login_accountLocked() {
        when(userMapper.selectOne(any())).thenReturn(user(1, LocalDateTime.now().plusMinutes(10)));

        assertThatThrownBy(() -> authService.login(new LoginRequest("admin", "x", false), request))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(ErrorCode.ACCOUNT_LOCKED.getCode());
                    assertThat(be.getMessage()).contains("分钟后重试");
                });
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("登录失败：密码错误累加失败次数，未达阈值不锁定")
    void login_passwordFail_increments() {
        SysUser u = user(1, null);
        when(userMapper.selectOne(any())).thenReturn(u);
        when(passwordEncoder.matches("wrong", u.getPassword())).thenReturn(false);
        when(configService.getInt("login_fail_limit", 5)).thenReturn(5);

        assertThatThrownBy(() -> authService.login(new LoginRequest("admin", "wrong", false), request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.USERNAME_PASSWORD_ERROR.getCode());
        assertThat(u.getFailCount()).isEqualTo(1);
        assertThat(u.getLockUntil()).isNull();
        verify(userMapper).updateById(u);
    }

    @Test
    @DisplayName("登录失败：密码错误达阈值锁定账号并清零失败次数")
    void login_passwordFail_locksAtThreshold() {
        SysUser u = user(1, null);
        u.setFailCount(4);
        when(userMapper.selectOne(any())).thenReturn(u);
        when(passwordEncoder.matches("wrong", u.getPassword())).thenReturn(false);
        when(configService.getInt("login_fail_limit", 5)).thenReturn(5);
        when(configService.getInt("lock_minutes", 15)).thenReturn(15);

        assertThatThrownBy(() -> authService.login(new LoginRequest("admin", "wrong", false), request))
                .isInstanceOf(BusinessException.class);
        assertThat(u.getLockUntil()).isAfter(LocalDateTime.now());
        assertThat(u.getFailCount()).isZero();
    }

    @Test
    @DisplayName("登录限流触发返回 429，不查询用户表")
    void login_rateLimited() {
        when(loginRateLimiter.tryAcquire(anyString())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("admin", "x", false), request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS.getCode());
        verify(userMapper, never()).selectOne(any());
    }
}
