package com.smms.sys.service;

import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.security.LoginUser;
import com.smms.sys.entity.SysUser;
import com.smms.sys.mapper.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 敏感操作二次密码确认测试（BR-SYS-06）：空密码、错密码拒绝，正确密码放行。
 */
@ExtendWith(MockitoExtension.class)
class SensitiveAuthServiceTest {

    private static final long USER_ID = 5L;

    @Mock private SysUserMapper userMapper;
    @Mock private PasswordEncoder passwordEncoder;

    private SensitiveAuthService service;

    @BeforeEach
    void setUp() {
        service = new SensitiveAuthService(userMapper, passwordEncoder);
        LoginUser loginUser = new LoginUser(USER_ID, "manager", "王店长",
                List.of("MANAGER"), List.of("店长"), Set.of(), null, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("密码为空：拒绝并提示需二次确认（BR-SYS-06）")
    void confirmPassword_blank_rejected() {
        assertThatThrownBy(() -> service.confirmPassword(" "))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.SENSITIVE_PWD_REQUIRED.getCode());

        assertThatThrownBy(() -> service.confirmPassword(null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.SENSITIVE_PWD_REQUIRED.getCode());
    }

    @Test
    @DisplayName("密码错误：拒绝且操作不执行（BR-SYS-06）")
    void confirmPassword_wrong_rejected() {
        SysUser user = new SysUser();
        user.setId(USER_ID);
        user.setPassword("HASH");
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(passwordEncoder.matches("wrong", "HASH")).thenReturn(false);

        assertThatThrownBy(() -> service.confirmPassword("wrong"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.SENSITIVE_PWD_ERROR.getCode());
    }

    @Test
    @DisplayName("密码正确：放行（BR-SYS-06）")
    void confirmPassword_correct_passed() {
        SysUser user = new SysUser();
        user.setId(USER_ID);
        user.setPassword("HASH");
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(passwordEncoder.matches("correct", "HASH")).thenReturn(true);

        assertThatCode(() -> service.confirmPassword("correct")).doesNotThrowAnyException();
    }
}
