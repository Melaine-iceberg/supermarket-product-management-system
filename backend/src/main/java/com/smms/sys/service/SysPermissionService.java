package com.smms.sys.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smms.sys.dto.MenuNode;
import com.smms.sys.entity.SysPermission;
import com.smms.sys.mapper.SysPermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 权限服务（FR-SYS-05）：目录/菜单/按钮三级树；
 * 为用户计算动态菜单树，为角色管理提供完整权限树。
 */
@Service
@RequiredArgsConstructor
public class SysPermissionService {

    private final SysPermissionMapper permissionMapper;

    /** 用户可见菜单树（目录 + 菜单，不含按钮） */
    public List<MenuNode> getMenuTreeByUserId(Long userId) {
        List<SysPermission> perms = permissionMapper.selectByUserId(userId);
        return buildTree(perms, false);
    }

    /** 完整权限树（角色分配界面用） */
    public List<MenuNode> getFullTree() {
        List<SysPermission> perms = permissionMapper.selectList(
                Wrappers.<SysPermission>lambdaQuery().eq(SysPermission::getStatus, 1));
        return buildTree(perms, true);
    }

    private List<MenuNode> buildTree(List<SysPermission> perms, boolean includeButtons) {
        List<MenuNode> nodes = perms.stream()
                .filter(p -> includeButtons || !SysPermission.TYPE_BUTTON.equals(p.getPermType()))
                .map(this::toNode)
                .toList();
        return nodes.stream()
                .filter(n -> n.parentId() == 0)
                .peek(n -> fillChildren(n, nodes))
                .sorted(Comparator.comparing(MenuNode::sortNo))
                .toList();
    }

    private void fillChildren(MenuNode parent, List<MenuNode> all) {
        List<MenuNode> children = all.stream()
                .filter(n -> parent.id().equals(n.parentId()))
                .peek(n -> fillChildren(n, all))
                .sorted(Comparator.comparing(MenuNode::sortNo))
                .toList();
        if (!children.isEmpty() && parent.children() instanceof List<MenuNode> list) {
            list.addAll(children);
        }
    }

    private MenuNode toNode(SysPermission p) {
        return new MenuNode(p.getId(), p.getParentId(), p.getName(), p.getPermType(),
                p.getPermCode(), p.getRoutePath(), p.getComponent(), p.getIcon(), p.getSortNo(),
                new java.util.ArrayList<>());
    }
}
