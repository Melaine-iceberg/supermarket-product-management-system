package com.smms.goods.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.goods.dto.CategoryNode;
import com.smms.goods.dto.CategorySaveRequest;
import com.smms.goods.dto.CategoryStatusRequest;
import com.smms.goods.service.CategoryService;
import com.smms.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品分类接口（FR-CAT-01~05）：树查询、增删改、停用/启用。
 */
@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    /** 分类树（FR-CAT-01）：含每个节点子树汇总的商品数量；分类维护页或业务只读选品均可 */
    @GetMapping
    @RequirePerm({"sys:cat:list", "sys:cat:tree"})
    public ApiResponse<List<CategoryNode>> tree() {
        return ApiResponse.ok(categoryService.tree());
    }

    @PostMapping
    @RequirePerm("sys:cat:create")
    @OpLog(module = "商品管理", action = "新增分类", target = "#req.name")
    public ApiResponse<Void> create(@Valid @RequestBody CategorySaveRequest req) {
        categoryService.create(req, SecurityUtils.getLoginUser().username());
        return ApiResponse.ok();
    }

    @PutMapping("/{id}")
    @RequirePerm("sys:cat:update")
    @OpLog(module = "商品管理", action = "编辑分类", target = "#id + '/' + #req.name")
    public ApiResponse<Void> update(@PathVariable Long id,
                                    @Valid @RequestBody CategorySaveRequest req) {
        categoryService.update(id, req, SecurityUtils.getLoginUser().username());
        return ApiResponse.ok();
    }

    /** 停用/启用（FR-CAT-04）：停用级联子孙；启用要求祖先已启用 */
    @PutMapping("/{id}/status")
    @RequirePerm("sys:cat:status")
    @OpLog(module = "商品管理", action = "停用启用分类", target = "#id")
    public ApiResponse<Void> updateStatus(@PathVariable Long id,
                                          @Valid @RequestBody CategoryStatusRequest req) {
        categoryService.changeStatus(id, req.status(), SecurityUtils.getLoginUser().username());
        return ApiResponse.ok();
    }

    /** 删除（FR-CAT-05，BR-CAT-03）：仅无子分类且无商品关联时可删 */
    @DeleteMapping("/{id}")
    @RequirePerm("sys:cat:delete")
    @OpLog(module = "商品管理", action = "删除分类", target = "#id")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return ApiResponse.ok();
    }
}
