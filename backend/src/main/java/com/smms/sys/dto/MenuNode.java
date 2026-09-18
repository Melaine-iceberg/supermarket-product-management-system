package com.smms.sys.dto;

import java.util.List;

/**
 * 前端动态菜单节点（FR-SYS-05）：目录/菜单两级；按钮权限通过 permCodes 控制。
 */
public record MenuNode(
        Long id,
        Long parentId,
        String name,
        String permType,
        String permCode,
        String routePath,
        String component,
        String icon,
        Integer sortNo,
        List<MenuNode> children
) {
}
