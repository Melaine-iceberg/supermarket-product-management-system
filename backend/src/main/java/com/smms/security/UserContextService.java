package com.smms.security;

import com.smms.sys.entity.SysPermission;
import com.smms.sys.entity.SysRole;
import com.smms.sys.entity.SysUser;
import com.smms.sys.mapper.SysPermissionMapper;
import com.smms.sys.mapper.SysRoleMapper;
import com.smms.sys.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 登录用户上下文缓存（NFR-SEC-02）：
 * userId → LoginUser，TTL 60 秒；用户/角色/权限变更时主动失效并自增全局权限版本号，
 * 携带旧版本号的 Token 立即强制重建，保证权限变更 ≤ 1 分钟生效。
 */
@Service
@RequiredArgsConstructor
public class UserContextService {

    private static final long TTL_MILLIS = 60_000L;

    private record CacheEntry(LoginUser user, long cachedAt, long version) {
    }

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;
    private final GlobalPermVersion permVersion;

    private final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * 获取登录用户上下文：
     * 仅当 Token 版本号与当前全局版本一致、且缓存新鲜时才直接复用缓存；
     * Token 版本落后（签发后发生过用户/角色/权限变更）或缓存过期/不存在时，从数据库重建。
     * 注意是"重建而非拒绝"：历史 Token 在权限变更后按最新权限继续可用，保证变更即时生效（NFR-SEC-02）。
     *
     * @param tokenVersion JWT 载荷中的权限版本号（登录时为 null，视为须重建）
     * @return 用户不存在或已停用时返回 null
     */
    public LoginUser getLoginUser(Long userId, Long tokenVersion) {
        long current = permVersion.current();
        boolean versionMatched = tokenVersion != null && tokenVersion == current;
        CacheEntry entry = cache.get(userId);
        if (entry != null && versionMatched && entry.version() == current
                && System.currentTimeMillis() - entry.cachedAt() < TTL_MILLIS) {
            return entry.user();
        }
        LoginUser user = buildFromDb(userId);
        if (user == null) {
            cache.remove(userId);
            return null;
        }
        cache.put(userId, new CacheEntry(user, System.currentTimeMillis(), current));
        return user;
    }

    public void invalidate(Long userId) {
        cache.remove(userId);
        permVersion.bump();
    }

    public void invalidateAll() {
        cache.clear();
        permVersion.bump();
    }

    private LoginUser buildFromDb(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            return null;
        }
        List<SysRole> roles = roleMapper.selectByUserId(userId);
        List<SysPermission> perms = permissionMapper.selectByUserId(userId);
        Set<String> permCodes = perms.stream()
                .map(SysPermission::getPermCode)
                .filter(c -> c != null && !c.isBlank())
                .collect(Collectors.toSet());
        return new LoginUser(
                user.getId(),
                user.getUsername(),
                user.getRealName(),
                roles.stream().map(SysRole::getRoleCode).toList(),
                roles.stream().map(SysRole::getRoleName).toList(),
                permCodes,
                pwdFp(user.getPassword()),
                user.getMustChangePwd() != null && user.getMustChangePwd() == 1
        );
    }

    /** 密码指纹：取 BCrypt 哈希的 SHA-256 前 16 位，用于密码修改后旧 Token 失效比对 */
    public static String pwdFp(String passwordHash) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(passwordHash.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 排除已停用角色，用于清理角色下线后的缓存（预留） */
    public void evictUsersOfRole(Long roleId) {
        // 单实例部署下直接全量失效，简单可靠
        invalidateAll();
    }
}
