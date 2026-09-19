package com.smms.goods.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.goods.dto.UnitSaveRequest;
import com.smms.goods.entity.Unit;
import com.smms.goods.service.UnitService;
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
 * 计量单位接口（FR-GOODS-08）。
 */
@RestController
@RequestMapping("/api/v1/units")
@RequiredArgsConstructor
public class UnitController {

    private final UnitService unitService;

    @GetMapping
    @RequirePerm("sys:unit:list")
    public ApiResponse<List<Unit>> list() {
        return ApiResponse.ok(unitService.list());
    }

    @PostMapping
    @RequirePerm("sys:unit:create")
    @OpLog(module = "商品管理", action = "新增单位", target = "#req.name")
    public ApiResponse<Void> create(@Valid @RequestBody UnitSaveRequest req) {
        unitService.create(req, SecurityUtils.getLoginUser().username());
        return ApiResponse.ok();
    }

    @PutMapping("/{id}")
    @RequirePerm("sys:unit:update")
    @OpLog(module = "商品管理", action = "编辑单位", target = "#id + '/' + #req.name")
    public ApiResponse<Void> update(@PathVariable Long id,
                                    @Valid @RequestBody UnitSaveRequest req) {
        unitService.update(id, req, SecurityUtils.getLoginUser().username());
        return ApiResponse.ok();
    }
}
