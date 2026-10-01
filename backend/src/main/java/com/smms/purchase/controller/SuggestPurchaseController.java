package com.smms.purchase.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.common.api.PageVO;
import com.smms.purchase.dto.SuggestGenerateRequest;
import com.smms.purchase.dto.SuggestGenerateResultVO;
import com.smms.purchase.dto.SuggestItemVO;
import com.smms.purchase.service.SuggestPurchaseService;
import com.smms.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 建议采购清单接口（FR-PUR-02，BR-INV-07）：
 * 缺货/预警商品清单（含建议采购量与计算依据）、按默认供应商分组一键生成草稿采购单。
 */
@RestController
@RequestMapping("/api/v1/suggest-purchase")
@RequiredArgsConstructor
public class SuggestPurchaseController {

    private final SuggestPurchaseService suggestPurchaseService;

    /** 建议采购清单分页：商品名/编码/分类/缺货类型（OUT=缺货、WARNING=预警） */
    @GetMapping
    @RequirePerm("sys:suggest:list")
    public ApiResponse<PageVO<SuggestItemVO>> page(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String goodsCode,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String alertType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(suggestPurchaseService.page(name, goodsCode, categoryId, alertType, page, size));
    }

    /** 一键生成草稿采购单（FR-PUR-02）：按默认供应商分组；缺供应商或不建议采购的商品返回跳过原因 */
    @PostMapping("/generate")
    @RequirePerm("sys:pur:create")
    @OpLog(module = "采购管理", action = "建议采购生成草稿采购单")
    public ApiResponse<SuggestGenerateResultVO> generate(@Valid @RequestBody SuggestGenerateRequest req) {
        return ApiResponse.ok(suggestPurchaseService.generate(req, SecurityUtils.getLoginUser().realName()));
    }
}
