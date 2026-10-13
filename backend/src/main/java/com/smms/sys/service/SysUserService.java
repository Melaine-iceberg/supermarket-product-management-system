package com.smms.sys.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.ErrorCode;
import com.smms.common.api.PageVO;
import com.smms.common.exception.BusinessException;
import com.smms.security.UserContextService;
import com.smms.sys.dto.UserCreateRequest;
import com.smms.sys.dto.UserUpdateRequest;
import com.smms.sys.dto.UserVO;
import com.smms.sys.entity.SysRole;
import com.smms.sys.entity.SysUser;
import com.smms.sys.entity.SysUserRole;
import com.smms.sys.mapper.SysRoleMapper;
import com.smms.sys.mapper.SysUserMapper;
import com.smms.sys.mapper.SysUserRoleMapper;
import com.smms.common.util.TextSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户管理服务（FR-SYS-02）：增、改、停用/启用、重置密码、分配角色；
 * 用户不可物理删除（BR-SYS-06）；超级管理员账号唯一、不可停用、角色不可被剥夺（BR-SYS-03）。
 */
@Service
@RequiredArgsConstructor
public class SysUserService {

    /** 超级管理员账号（BR-SYS-03） */
    public static final long ADMIN_USER_ID = 1L;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final UserContextService userContextService;

    public PageVO<UserVO> page(String username, Integer status, int page, int size) {
        Page<SysUser> pager = userMapper.selectPage(
                Page.of(page, size),
                Wrappers.<SysUser>lambdaQuery()
                        .like(username != null && !username.isBlank(), SysUser::getUsername, username)
                        .eq(status != null, SysUser::getStatus, status)
                        .orderByAsc(SysUser::getId));
        List<UserVO> vos = fillRoles(pager.getRecords().stream().map(this::toVO).toList());
        return new PageVO<>(vos, pager.getTotal());
    }

    /**
     * 新增用户（FR-SYS-02、BR-SYS-02）：
     * 初始密码随机生成并强制首次登录修改。
     */
    @Transactional
    public UserCreated create(UserCreateRequest req, String operator) {
        Long count = userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, req.username()));
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS);
        }
        List<Long> roleIds = normalizeRoleIds(req.roleIds());
        if (roleIds.contains(SysRole.SUPER_ADMIN_ROLE_ID)) {
            throw new BusinessException(ErrorCode.SUPER_ADMIN_ROLE_PROTECTED);
        }
        String initialPassword = randomPassword();

        SysUser user = new SysUser();
        user.setUsername(req.username());
        user.setPassword(passwordEncoder.encode(initialPassword));
        user.setRealName(req.realName());
        user.setPhone(blankToNull(req.phone()));
        user.setStatus(1);
        user.setMustChangePwd(1);
        user.setFailCount(0);
        user.setRemark(TextSanitizer.sanitize(req.remark()));
        user.setCreateBy(operator);
        userMapper.insert(user);

        saveRoles(user.getId(), roleIds, operator);
        userContextService.invalidate(user.getId());
        return new UserCreated(toVO(userMapper.selectById(user.getId())), initialPassword);
    }

    /** 编辑用户：用户名不可改；角色变更后权限即时生效（NFR-SEC-02） */
    @Transactional
    public void update(Long id, UserUpdateRequest req, String operator) {
        SysUser user = requireUser(id);
        user.setRealName(req.realName());
        user.setPhone(blankToNull(req.phone()));
        user.setRemark(TextSanitizer.sanitize(req.remark()));
        user.setUpdateBy(operator);
        userMapper.updateById(user);

        if (req.roleIds() != null) {
            List<Long> roleIds = normalizeRoleIds(req.roleIds());
            if (id == ADMIN_USER_ID && !roleIds.contains(SysRole.SUPER_ADMIN_ROLE_ID)) {
                throw new BusinessException(ErrorCode.BUILTIN_PROTECTED,
                        "超级管理员账号必须保留超级管理员角色（BR-SYS-03）");
            }
            if (id != ADMIN_USER_ID && roleIds.contains(SysRole.SUPER_ADMIN_ROLE_ID)) {
                throw new BusinessException(ErrorCode.SUPER_ADMIN_ROLE_PROTECTED);
            }
            userRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, id));
            saveRoles(id, roleIds, operator);
            userContextService.invalidateAll();
        } else {
            userContextService.invalidate(id);
        }
    }

    /** 停用/启用：停用后 Token 立即失效（FR-SYS-02） */
    @Transactional
    public void updateStatus(Long id, int status, String operator) {
        if (id == ADMIN_USER_ID && status != 1) {
            throw new BusinessException(ErrorCode.BUILTIN_PROTECTED,
                    "超级管理员账号不可停用（BR-SYS-03）");
        }
        SysUser user = requireUser(id);
        user.setStatus(status);
        user.setUpdateBy(operator);
        userMapper.updateById(user);
        userContextService.invalidate(id);
    }

    /** 重置密码（BR-SYS-02）：随机初始密码 + 强制首次登录修改 + 解锁 */
    @Transactional
    public String resetPassword(Long id, String operator) {
        SysUser user = requireUser(id);
        String newPassword = randomPassword();
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePwd(1);
        user.setFailCount(0);
        user.setLockUntil(null);
        user.setUpdateBy(operator);
        userMapper.updateById(user);
        userContextService.invalidate(id);
        return newPassword;
    }

    private SysUser requireUser(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    private List<Long> normalizeRoleIds(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        List<Long> distinct = roleIds.stream().distinct().toList();
        List<Long> validIds = roleMapper.selectBatchIds(distinct).stream()
                .filter(r -> r.getStatus() != null && r.getStatus() == 1)
                .map(SysRole::getId)
                .toList();
        if (validIds.size() != distinct.size()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "包含不存在或已停用的角色");
        }
        return distinct;
    }

    private void saveRoles(Long userId, List<Long> roleIds, String operator) {
        for (Long roleId : roleIds) {
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(userId);
            userRole.setRoleId(roleId);
            // 审计字段（DR-C-08/GAP-P2-28）：关联创建人=经办管理员
            userRole.setCreateBy(operator);
            userRole.setCreateTime(LocalDateTime.now());
            userRoleMapper.insert(userRole);
        }
    }

    private UserVO toVO(SysUser user) {
        return new UserVO(user.getId(), user.getUsername(), user.getRealName(), user.getPhone(),
                user.getStatus(), user.getRemark(), List.of(), List.of(), user.getLastLoginTime(),
                user.getMustChangePwd() != null && user.getMustChangePwd() == 1, user.getCreateTime());
    }

    /** 批量填充角色信息 */
    private List<UserVO> fillRoles(List<UserVO> vos) {
        if (vos.isEmpty()) {
            return vos;
        }
        List<Long> userIds = vos.stream().map(UserVO::id).toList();
        List<SysUserRole> relations = userRoleMapper.selectList(
                Wrappers.<SysUserRole>lambdaQuery().in(SysUserRole::getUserId, userIds));
        if (relations.isEmpty()) {
            return vos;
        }
        List<Long> roleIds = relations.stream().map(SysUserRole::getRoleId).distinct().toList();
        Map<Long, String> roleNames = roleMapper.selectBatchIds(roleIds).stream()
                .collect(Collectors.toMap(SysRole::getId, SysRole::getRoleName));
        Map<Long, List<SysUserRole>> byUser = relations.stream()
                .collect(Collectors.groupingBy(SysUserRole::getUserId));
        return vos.stream().map(vo -> {
            List<SysUserRole> userRoles = byUser.getOrDefault(vo.id(), List.of());
            List<Long> ids = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (SysUserRole userRole : userRoles) {
                ids.add(userRole.getRoleId());
                names.add(roleNames.getOrDefault(userRole.getRoleId(), "未知角色"));
            }
            return new UserVO(vo.id(), vo.username(), vo.realName(), vo.phone(), vo.status(),
                    vo.remark(), ids, names, vo.lastLoginTime(), vo.mustChangePwd(), vo.createTime());
        }).toList();
    }

    /** 随机初始密码（BR-SYS-02）：10 位，保证含字母与数字 */
    private String randomPassword() {
        String letters = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz";
        String digits = "23456789";
        String all = letters + digits;
        List<Character> chars = new ArrayList<>();
        chars.add(letters.charAt(RANDOM.nextInt(letters.length())));
        chars.add(digits.charAt(RANDOM.nextInt(digits.length())));
        for (int i = 2; i < 10; i++) {
            chars.add(all.charAt(RANDOM.nextInt(all.length())));
        }
        Collections.shuffle(chars, RANDOM);
        StringBuilder result = new StringBuilder();
        chars.forEach(result::append);
        return result.toString();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 新增用户结果：用户视图 + 一次性初始密码 */
    public record UserCreated(UserVO user, String initialPassword) {
    }
}
