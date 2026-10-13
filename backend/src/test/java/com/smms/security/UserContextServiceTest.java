package com.smms.security;

import com.smms.sys.entity.SysUser;
import com.smms.sys.mapper.SysPermissionMapper;
import com.smms.sys.mapper.SysRoleMapper;
import com.smms.sys.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录上下文与权限版本号单元测试（GAP-P1-11，NFR-SEC-02）：
 * JWT 载荷中的 ver 参与校验——版本落后（期间发生权限/角色/用户变更）时强制从数据库重建，
 * 权限变更对已登录用户即时生效；版本一致时 TTL 内复用缓存；停用用户返回 null。
 */
@ExtendWith(MockitoExtension.class)
class UserContextServiceTest {

    private static final long USER_ID = 7L;

    @Mock private SysUserMapper userMapper;
    @Mock private SysRoleMapper roleMapper;
    @Mock private SysPermissionMapper permissionMapper;

    private GlobalPermVersion permVersion;
    private UserContextService service;

    @BeforeEach
    void setUp() {
        permVersion = new GlobalPermVersion();
        service = new UserContextService(userMapper, roleMapper, permissionMapper, permVersion);
        lenient().when(roleMapper.selectByUserId(USER_ID)).thenReturn(List.of());
        lenient().when(permissionMapper.selectByUserId(USER_ID)).thenReturn(List.of());
    }

    private SysUser user(int status) {
        SysUser user = new SysUser();
        user.setId(USER_ID);
        user.setUsername("keeper");
        user.setRealName("赵库管");
        user.setPassword("$2b$10$hashhashhashhashhashhash");
        user.setStatus(status);
        user.setMustChangePwd(0);
        return user;
    }

    @Test
    @DisplayName("ver 版本落后（权限变更）：旧 Token 强制重建上下文，权限即时生效")
    void staleTokenVersionForcesContextRebuild() {
        when(userMapper.selectById(USER_ID)).thenReturn(user(1));
        long oldVersion = permVersion.current();

        LoginUser first = service.getLoginUser(USER_ID, oldVersion);
        assertThat(first).isNotNull();
        assertThat(first.username()).isEqualTo("keeper");

        // 版本一致且缓存新鲜：不再查库
        service.getLoginUser(USER_ID, oldVersion);
        verify(userMapper, times(1)).selectById(USER_ID);

        // 权限/角色/用户变更：全局版本自增，旧 Token ver 失配 → 重建
        permVersion.bump();
        service.getLoginUser(USER_ID, oldVersion);
        verify(userMapper, times(2)).selectById(USER_ID);
    }

    @Test
    @DisplayName("ver 版本一致：TTL 内复用缓存；登录（ver=null）始终重建")
    void freshVersionUsesCache() {
        when(userMapper.selectById(USER_ID)).thenReturn(user(1));
        long current = permVersion.current();

        service.getLoginUser(USER_ID, current);
        service.getLoginUser(USER_ID, current);
        verify(userMapper, times(1)).selectById(USER_ID);

        // 登录时传 null：视为须重建，保证拿到最新权限
        service.getLoginUser(USER_ID, null);
        verify(userMapper, times(2)).selectById(USER_ID);
    }

    @Test
    @DisplayName("用户已停用：重建返回 null（Token 不可用）")
    void disabledUserReturnsNull() {
        when(userMapper.selectById(USER_ID)).thenReturn(user(0));

        assertThat(service.getLoginUser(USER_ID, permVersion.current())).isNull();
    }
}
