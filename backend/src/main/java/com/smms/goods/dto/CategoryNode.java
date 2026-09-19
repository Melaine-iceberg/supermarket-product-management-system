package com.smms.goods.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 分类树节点（FR-CAT-01）：含子分类汇总的商品数量。
 */
public record CategoryNode(

        Long id,

        Long parentId,

        String name,

        /** 层级 1~3 */
        Integer level,

        Integer sortNo,

        /** 1启用 0停用 */
        Integer status,

        /** 是否叶子节点（无子分类，商品只能挂叶子，BR-CAT-04） */
        boolean leaf,

        /** 该分类及其子分类下的商品数量（汇总） */
        long goodsCount,

        List<CategoryNode> children
) {

    public static CategoryNode of(Long id, Long parentId, String name, Integer level,
                                  Integer sortNo, Integer status, long goodsCount) {
        return new CategoryNode(id, parentId, name, level, sortNo, status,
                true, goodsCount, new ArrayList<>());
    }

    /** 子分类装配后重算叶子标记与汇总商品数（不可变记录 → 生成新节点） */
    public CategoryNode withChildren(List<CategoryNode> children) {
        long total = goodsCount + children.stream().mapToLong(CategoryNode::goodsCount).sum();
        return new CategoryNode(id, parentId, name, level, sortNo, status,
                children.isEmpty(), total, children);
    }
}
