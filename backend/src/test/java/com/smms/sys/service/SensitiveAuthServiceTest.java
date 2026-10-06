package com.smms.sys.service;

import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.security.LoginUser;
import com.smms.sys.entity.SysRole;
import com.smms.sys.entity.SysUser;
import com.smms.sys.mapper.SysRoleMapper;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 敏感操作二次密码确认（BR-SYS-06）：空密码、错密码拒绝，正确密码放行；
 * 授权人身份校验（FR-SALE-07/11、FR-PUR-06）：账号+密码+店长角色三重核验。
 */
@ExtendWith(MockitoExtension.class)
class SensitiveAuthServiceTest {

    private static final long USER_ID = 5L;
    private static final long AUTHORIZER_ID = 9L;

    @Mock private SysUserMapper userMapper;
    @Mock private SysRoleMapper roleMapper;
    @Mock private PasswordEncoder passwordEncoder;

    private SensitiveAuthService service;

    @BeforeEach
    void setUp() {
        service = new SensitiveAuthService(userMapper, roleMapper, passwordEncoder);
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

    // ---------- 授权人身份校验（FR-SALE-07/11、FR-PUR-06） ----------

    @Test
    @DisplayName("授权账号或密码为空：拒绝（FR-SALE-07/11）")
    void authorize_blank_rejected() {
        assertThatThrownBy(() -> service.authorize(" ", "pwd"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.AUTHORIZER_AUTH_FAILED.getCode());
        assertThatThrownBy(() -> service.authorize("manager", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.AUTHORIZER_AUTH_FAILED.getCode());
    }

    @Test
    @DisplayName("授权账号不存在/停用/密码错误：统一拒绝（不泄露账号状态）")
    void authorize_accountMismatch_rejected() {
        when(userMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.authorize("ghost", "pwd"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.AUTHORIZER_AUTH_FAILED.getCode());

        SysUser disabled = user(AUTHORIZER_ID, 0, "HASH");
        when(userMapper.selectOne(any())).thenReturn(disabled);
        assertThatThrownBy(() -> service.authorize("manager", "pwd"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.AUTHORIZER_AUTH_FAILED.getCode());

        SysUser user = user(AUTHORIZER_ID, 1, "HASH");
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("wrong", "HASH")).thenReturn(false);
        assertThatThrownBy(() -> service.authorize("manager", "wrong"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.AUTHORIZER_AUTH_FAILED.getCode());
    }

    @Test
    @DisplayName("非店长账号（如收银员）：拒绝授权（FR-SALE-07/11）")
    void authorize_nonManagerRole_rejected() {
        when(userMapper.selectOne(any())).thenReturn(user(AUTHORIZER_ID, 1, "HASH"));
        when(passwordEncoder.matches("pwd", "HASH")).thenReturn(true);
        when(roleMapper.selectByUserId(AUTHORIZER_ID)).thenReturn(List.of(role("CASHIER", 1)));

        assertThatThrownBy(() -> service.authorize("cashier", "pwd"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.AUTHORIZER_NOT_MANAGER.getCode());
    }

    @Test
    @DisplayName("店长/超管账号密码正确：返回核验后的授权人信息；停用角色不算数")
    void authorize_manager_passed() {
        SysUser user = user(AUTHORIZER_ID, 1, "HASH");
        user.setUsername("manager");
        user.setRealName("王店长");
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("pwd", "HASH")).thenReturn(true);

        // 店长角色已停用 → 拒绝
        when(roleMapper.selectByUserId(AUTHORIZER_ID)).thenReturn(List.of(role("MANAGER", 0)));
        assertThatThrownBy(() -> service.authorize("manager", "pwd"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.AUTHORIZER_NOT_MANAGER.getCode());

        // 启用中的店长角色 → 通过
        when(roleMapper.selectByUserId(AUTHORIZER_ID)).thenReturn(List.of(role("MANAGER", 1)));
        SensitiveAuthService.Authorizer authorizer = service.authorize("manager", "pwd");
        assertThat(authorizer.userId()).isEqualTo(AUTHORIZER_ID);
        assertThat(authorizer.username()).isEqualTo("manager");
        assertThat(authorizer.realName()).isEqualTo("王店长");

        // 超管角色同样可授权（店长及以上）
        when(roleMapper.selectByUserId(AUTHORIZER_ID)).thenReturn(List.of(role("SUPER_ADMIN", 1)));
        assertThat(service.authorize("manager", "pwd").userId()).isEqualTo(AUTHORIZER_ID);
    }

    private SysUser user(long id, int status, String password) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername("manager");
        user.setRealName("王店长");
        user.setStatus(status);
        user.setPassword(password);
        return user;
    }

    private SysRole role(String roleCode, int status) {
        SysRole role = new SysRole();
        role.setRoleCode(roleCode);
        role.setStatus(status);
        return role;
    }
}
