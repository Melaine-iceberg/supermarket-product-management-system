package com.smms.goods.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.goods.dto.BrandSaveRequest;
import com.smms.goods.entity.Brand;
import com.smms.goods.service.BrandService;
import com.smms.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 品牌接口（FR-GOODS-13）。
 */
@RestController
@RequestMapping("/api/v1/brands")
@RequiredArgsConstructor
public class BrandController {

    private final BrandService brandService;

    @GetMapping
    @RequirePerm("sys:brand:list")
    public ApiResponse<List<Brand>> list() {
        return ApiResponse.ok(brandService.list());
    }

    @PostMapping
    @RequirePerm("sys:brand:create")
    @OpLog(module = "商品管理", action = "新增品牌", target = "#req.name")
    public ApiResponse<Void> create(@Valid @RequestBody BrandSaveRequest req) {
        brandService.create(req, SecurityUtils.getLoginUser().username());
        return ApiResponse.ok();
    }

    @PutMapping("/{id}")
    @RequirePerm("sys:brand:update")
    @OpLog(module = "商品管理", action = "编辑品牌", target = "#id + '/' + #req.name")
    public ApiResponse<Void> update(@PathVariable Long id,
                                    @Valid @RequestBody BrandSaveRequest req) {
        brandService.update(id, req, SecurityUtils.getLoginUser().username());
        return ApiResponse.ok();
    }
}
