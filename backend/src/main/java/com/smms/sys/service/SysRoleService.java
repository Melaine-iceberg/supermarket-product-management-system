package com.smms.sys.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.security.UserContextService;
import com.smms.sys.dto.RoleSaveRequest;
import com.smms.sys.dto.RoleVO;
import com.smms.sys.entity.SysRole;
import com.smms.sys.entity.SysRolePermission;
import com.smms.sys.mapper.SysPermissionMapper;
import com.smms.sys.mapper.SysRoleMapper;
import com.smms.sys.mapper.SysRolePermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 角色管理服务（FR-SYS-04、FR-SYS-05）：
 * 角色增、改、停用；内置角色不可删除；权限可调整；
 * 超级管理员角色权限不可被剥夺（BR-SYS-03）。
 */
@Service
@RequiredArgsConstructor
public class SysRoleService {

    private final SysRoleMapper roleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysPermissionMapper permissionMapper;
    private final UserContextService userContextService;

    public List<RoleVO> list() {
        return roleMapper.selectList(Wrappers.<SysRole>lambdaQuery()
                        .orderByAsc(SysRole::getId)).stream()
                .map(this::toVO)
                .toList();
    }

    @Transactional
    public void create(RoleSaveRequest req, String operator) {
        checkCodeAvailable(req.roleCode(), null);
        SysRole role = new SysRole();
        role.setRoleName(req.roleName());
        role.setRoleCode(req.roleCode());
        role.setDescription(req.description());
        role.setIsBuiltin(0);
        role.setStatus(1);
        role.setCreateBy(operator);
        roleMapper.insert(role);
    }

    @Transactional
    public void update(Long id, RoleSaveRequest req, String operator) {
        SysRole role = requireRole(id);
        // 内置角色代码不可修改（FR-SYS-04）
        if (role.getIsBuiltin() != null && role.getIsBuiltin() == 1
                && !role.getRoleCode().equals(req.roleCode())) {
            throw new BusinessException(ErrorCode.BUILTIN_PROTECTED, "内置角色的角色代码不可修改");
        }
        checkCodeAvailable(req.roleCode(), id);
        boolean codeChanged = !role.getRoleCode().equals(req.roleCode());
        role.setRoleName(req.roleName());
        role.setRoleCode(req.roleCode());
        role.setDescription(req.description());
        role.setUpdateBy(operator);
        roleMapper.updateById(role);
        if (codeChanged) {
            userContextService.invalidateAll();
        }
    }

    /** 停用/启用角色：超级管理员角色不可停用（BR-SYS-03） */
    @Transactional
    public void updateStatus(Long id, int status, String operator) {
        SysRole role = requireRole(id);
        if (SysRole.SUPER_ADMIN_ROLE_ID == id && status != 1) {
            throw new BusinessException(ErrorCode.BUILTIN_PROTECTED, "超级管理员角色不可停用（BR-SYS-03）");
        }
        role.setStatus(status);
        role.setUpdateBy(operator);
        roleMapper.updateById(role);
        userContextService.invalidateAll();
    }

    /** 角色已勾选的权限 ID 列表 */
    public List<Long> getPermIds(Long roleId) {
        requireRole(roleId);
        return rolePermissionMapper.selectList(Wrappers.<SysRolePermission>lambdaQuery()
                        .eq(SysRolePermission::getRoleId, roleId)).stream()
                .map(SysRolePermission::getPermId)
                .toList();
    }

    /** 分配权限（FR-SYS-05）：全量替换；变更后已登录用户即时生效（NFR-SEC-02） */
    @Transactional
    public void assignPerms(Long roleId, List<Long> permIds, String operator) {
        SysRole role = requireRole(roleId);
        if (SysRole.SUPER_ADMIN_ROLE_ID == roleId) {
            throw new BusinessException(ErrorCode.BUILTIN_PROTECTED,
                    "超级管理员角色权限不可被剥夺（BR-SYS-03）");
        }
        List<Long> distinct = permIds == null ? List.of() : permIds.stream().distinct().toList();
        if (!distinct.isEmpty()) {
            Long count = permissionMapper.selectCount(Wrappers.<com.smms.sys.entity.SysPermission>lambdaQuery()
                    .in(com.smms.sys.entity.SysPermission::getId, distinct));
            if (count == null || count != distinct.size()) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "包含不存在的权限项");
            }
        }
        rolePermissionMapper.delete(Wrappers.<SysRolePermission>lambdaQuery()
                .eq(SysRolePermission::getRoleId, roleId));
        for (Long permId : distinct) {
            SysRolePermission rp = new SysRolePermission();
            rp.setRoleId(roleId);
            rp.setPermId(permId);
            rolePermissionMapper.insert(rp);
        }
        role.setUpdateBy(operator);
        roleMapper.updateById(role);
        userContextService.invalidateAll();
    }

    private SysRole requireRole(Long id) {
        SysRole role = roleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "角色不存在");
        }
        return role;
    }

    private void checkCodeAvailable(String roleCode, Long excludeId) {
        Long count = roleMapper.selectCount(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getRoleCode, roleCode)
                .ne(excludeId != null, SysRole::getId, excludeId));
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.ROLE_CODE_EXISTS);
        }
    }

    private RoleVO toVO(SysRole role) {
        return new RoleVO(role.getId(), role.getRoleName(), role.getRoleCode(), role.getDescription(),
                role.getIsBuiltin() != null && role.getIsBuiltin() == 1, role.getStatus(),
                role.getCreateTime());
    }
}
