package com.smms.sys.service;

import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.security.UserContextService;
import com.smms.sys.dto.UserCreateRequest;
import com.smms.sys.dto.UserUpdateRequest;
import com.smms.sys.entity.SysRole;
import com.smms.sys.entity.SysUser;
import com.smms.sys.entity.SysUserRole;
import com.smms.sys.mapper.SysRoleMapper;
import com.smms.sys.mapper.SysUserMapper;
import com.smms.sys.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户管理核心规则测试：BR-SYS-03 超级管理员账号唯一、角色不可被剥夺、不可停用。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SysUserServiceTest {

    private static final long ADMIN_ID = SysUserService.ADMIN_USER_ID;
    private static final long OTHER_USER_ID = 2L;
    private static final long MANAGER_ROLE_ID = 2L;
    private static final String OPERATOR = "王店长";

    @Mock private SysUserMapper userMapper;
    @Mock private SysRoleMapper roleMapper;
    @Mock private SysUserRoleMapper userRoleMapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private UserContextService userContextService;

    private SysUserService service;

    @BeforeEach
    void setUp() {
        service = new SysUserService(userMapper, roleMapper, userRoleMapper,
                passwordEncoder, userContextService);
        when(passwordEncoder.encode(any())).thenReturn("ENC");
    }

    private SysRole role(long id, String code, String name, int status) {
        SysRole r = new SysRole();
        r.setId(id);
        r.setRoleCode(code);
        r.setRoleName(name);
        r.setStatus(status);
        return r;
    }

    private SysUser user(long id, String username) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setUsername(username);
        u.setRealName("测试用户");
        u.setPassword("HASH");
        u.setStatus(1);
        u.setMustChangePwd(1);
        return u;
    }

    // ---------- 新增用户（BR-SYS-03） ----------

    @Test
    @DisplayName("新增用户授予超级管理员角色：拒绝且不落库（BR-SYS-03）")
    void create_withSuperAdminRole_rejected() {
        when(roleMapper.selectBatchIds(any())).thenReturn(List.of(
                role(SysRole.SUPER_ADMIN_ROLE_ID, "SUPER_ADMIN", "超级管理员", 1)));

        assertThatThrownBy(() -> service.create(new UserCreateRequest(
                "hacker", "张三", null, null, List.of(SysRole.SUPER_ADMIN_ROLE_ID)), OPERATOR))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.SUPER_ADMIN_ROLE_PROTECTED.getCode());

        verify(userMapper, never()).insert(any(SysUser.class));
        verify(userRoleMapper, never()).insert(any(SysUserRole.class));
    }

    @Test
    @DisplayName("新增用户授予普通角色：成功并生成一次性初始密码、强制改密（FR-SYS-02/BR-SYS-02）")
    void create_normalRole_success() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(roleMapper.selectBatchIds(any())).thenReturn(List.of(role(MANAGER_ROLE_ID, "MANAGER", "店长", 1)));
        doAnswer(inv -> {
            SysUser u = inv.getArgument(0);
            u.setId(9L);
            return 1;
        }).when(userMapper).insert(any(SysUser.class));
        when(userMapper.selectById(9L)).thenAnswer(inv -> user(9L, "newuser"));

        SysUserService.UserCreated created = service.create(
                new UserCreateRequest("newuser", "李四", null, null, List.of(MANAGER_ROLE_ID)), OPERATOR);

        assertThat(created.initialPassword()).hasSize(10);
        assertThat(created.user().id()).isEqualTo(9L);
        verify(userMapper).insert(any(SysUser.class));
        verify(userRoleMapper).insert(any(SysUserRole.class));
    }

    // ---------- 编辑用户（BR-SYS-03） ----------

    @Test
    @DisplayName("为非初始账号授予超级管理员角色：拒绝且不变更角色（BR-SYS-03）")
    void update_grantSuperAdminToOtherUser_rejected() {
        when(userMapper.selectById(OTHER_USER_ID)).thenReturn(user(OTHER_USER_ID, "manager"));
        when(roleMapper.selectBatchIds(any())).thenReturn(List.of(
                role(SysRole.SUPER_ADMIN_ROLE_ID, "SUPER_ADMIN", "超级管理员", 1)));

        assertThatThrownBy(() -> service.update(OTHER_USER_ID, new UserUpdateRequest(
                "王店长", null, null, List.of(SysRole.SUPER_ADMIN_ROLE_ID)), OPERATOR))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.SUPER_ADMIN_ROLE_PROTECTED.getCode());

        verify(userRoleMapper, never()).delete(any());
        verify(userRoleMapper, never()).insert(any(SysUserRole.class));
    }

    @Test
    @DisplayName("初始账号保留超管角色可正常编辑，剥离超管角色被拒（BR-SYS-03）")
    void update_adminUserRoleProtection() {
        when(userMapper.selectById(ADMIN_ID)).thenReturn(user(ADMIN_ID, "admin"));
        when(roleMapper.selectBatchIds(any())).thenReturn(List.of(
                role(SysRole.SUPER_ADMIN_ROLE_ID, "SUPER_ADMIN", "超级管理员", 1),
                role(MANAGER_ROLE_ID, "MANAGER", "店长", 1)));

        service.update(ADMIN_ID, new UserUpdateRequest("系统管理员", null, null,
                List.of(SysRole.SUPER_ADMIN_ROLE_ID, MANAGER_ROLE_ID)), OPERATOR);
        verify(userRoleMapper, times(2)).insert(any(SysUserRole.class));
        clearInvocations(userRoleMapper);

        when(roleMapper.selectBatchIds(any())).thenReturn(List.of(
                role(MANAGER_ROLE_ID, "MANAGER", "店长", 1)));
        assertThatThrownBy(() -> service.update(ADMIN_ID, new UserUpdateRequest(
                "系统管理员", null, null, List.of(MANAGER_ROLE_ID)), OPERATOR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超级管理员账号必须保留超级管理员角色");
        verify(userRoleMapper, never()).insert(any(SysUserRole.class));
    }

    @Test
    @DisplayName("初始账号不可停用（BR-SYS-03）")
    void updateStatus_disableAdmin_rejected() {
        assertThatThrownBy(() -> service.updateStatus(ADMIN_ID, 0, OPERATOR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不可停用");
        verify(userMapper, never()).updateById(any(SysUser.class));
    }
}
