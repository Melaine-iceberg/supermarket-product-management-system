package com.smms.goods.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.goods.dto.PromotionCreateRequest;
import com.smms.goods.entity.Promotion;
import com.smms.goods.service.PromotionService;
import com.smms.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 促销接口（FR-GOODS-09）：批量设置、停用、按商品查询；
 * 同一商品同一时间段仅一条生效促销（BR-GOODS-04）。
 */
@RestController
@RequestMapping("/api/v1/promotions")
@RequiredArgsConstructor
public class PromotionController {

    private final PromotionService promotionService;

    /** 设置促销（单个/批量），促销写操作复用商品编辑权限 */
    @PostMapping
    @RequirePerm("sys:goods:update")
    @OpLog(module = "商品管理", action = "设置促销")
    public ApiResponse<Void> create(@Valid @RequestBody PromotionCreateRequest req) {
        promotionService.create(req, SecurityUtils.getLoginUser().username());
        return ApiResponse.ok();
    }

    /** 停用生效中的促销 */
    @PutMapping("/{id}/disable")
    @RequirePerm("sys:goods:update")
    @OpLog(module = "商品管理", action = "停用促销", target = "#id")
    public ApiResponse<Void> disable(@PathVariable Long id) {
        promotionService.disable(id);
        return ApiResponse.ok();
    }

    /** 按商品查询促销记录（开始时间倒序） */
    @GetMapping
    @RequirePerm("sys:goods:list")
    public ApiResponse<List<Promotion>> listByGoods(@RequestParam Long goodsId) {
        return ApiResponse.ok(promotionService.listByGoods(goodsId));
    }
}
