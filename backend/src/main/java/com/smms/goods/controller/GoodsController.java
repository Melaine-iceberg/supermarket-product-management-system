package com.smms.goods.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.common.api.PageVO;
import com.smms.goods.dto.GoodsCreateRequest;
import com.smms.goods.dto.GoodsCreatedVO;
import com.smms.goods.dto.GoodsDetailVO;
import com.smms.goods.dto.GoodsListVO;
import com.smms.goods.dto.GoodsQuery;
import com.smms.goods.dto.GoodsStatusRequest;
import com.smms.goods.dto.GoodsUpdateRequest;
import com.smms.goods.entity.PriceHistory;
import com.smms.goods.service.GoodsService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
/**
 * 商品档案接口（FR-GOODS-01~07/10）：
 * 列表多维检索、详情、新增、编辑、停用/启用、删除、店内码预览、调价历史。
 */
@RestController
@RequestMapping("/api/v1/goods")
@RequiredArgsConstructor
public class GoodsController {

    private final GoodsService goodsService;

    /** 列表多维检索（FR-GOODS-03）：默认每页 20 条，可选 20/50/100 */
    @GetMapping
    @RequirePerm("sys:goods:list")
    public ApiResponse<PageVO<GoodsListVO>> page(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String goodsCode,
            @RequestParam(required = false) String barcode,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String stockStatus,
            @RequestParam(required = false) Integer isWeighed,
            @RequestParam(required = false) Integer allowReturn,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        GoodsQuery query = new GoodsQuery(name, goodsCode, barcode, categoryId, brandId,
                status, stockStatus, isWeighed, allowReturn, page, size, sortBy, sortDir);
        return ApiResponse.ok(goodsService.page(query));
    }

    /** 商品详情（FR-GOODS-04）：档案 + 库存成本 + 流水/采购价/销量/供应商聚合 */
    @GetMapping("/{id}/detail")
    @RequirePerm("sys:goods:list")
    public ApiResponse<GoodsDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(goodsService.detail(id));
    }

    /** 调价历史（FR-GOODS-10）：按商品查询完整价格轨迹 */
    @GetMapping("/{id}/price-history")
    @RequirePerm("sys:goods:list")
    public ApiResponse<List<PriceHistory>> priceHistory(@PathVariable Long id) {
        return ApiResponse.ok(goodsService.priceHistory(id));
    }

    /** 新增商品（FR-GOODS-01）：编码/条码留空自动生成，回显生成值 */
    @PostMapping
    @RequirePerm("sys:goods:create")
    @OpLog(module = "商品管理", action = "新增商品", target = "#req.name")
    public ApiResponse<GoodsCreatedVO> create(@Valid @RequestBody GoodsCreateRequest req) {
        return ApiResponse.ok(goodsService.create(req, SecurityUtils.getLoginUser().username()));
    }

    /** 编辑商品（FR-GOODS-02）：编码不可改；条码修改须确认；调价留痕 */
    @PutMapping("/{id}")
    @RequirePerm("sys:goods:update")
    @OpLog(module = "商品管理", action = "编辑商品", target = "#id")
    public ApiResponse<Void> update(@PathVariable Long id,
                                    @Valid @RequestBody GoodsUpdateRequest req) {
        goodsService.update(id, req, SecurityUtils.getLoginUser().username());
        return ApiResponse.ok();
    }

    /** 停用/启用（FR-GOODS-05）：停用后不可销售/采购，档案永久保留 */
    @PutMapping("/{id}/status")
    @RequirePerm("sys:goods:status")
    @OpLog(module = "商品管理", action = "停用启用商品", target = "#id")
    public ApiResponse<Void> updateStatus(@PathVariable Long id,
                                          @Valid @RequestBody GoodsStatusRequest req) {
        goodsService.changeStatus(id, req.status(), SecurityUtils.getLoginUser().username());
        return ApiResponse.ok();
    }

    /** 删除（FR-GOODS-06，BR-GOODS-06）：仅从未发生业务的商品可删 */
    @DeleteMapping("/{id}")
    @RequirePerm("sys:goods:delete")
    @OpLog(module = "商品管理", action = "删除商品", target = "#id")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        goodsService.delete(id);
        return ApiResponse.ok();
    }

    /** 店内码预览（FR-GOODS-07）：EAN-13 前缀20~29 + 序号 + 校验位，不落库 */
    @GetMapping("/next-barcode")
    @RequirePerm("sys:goods:create")
    public ApiResponse<Map<String, String>> nextBarcode() {
        return ApiResponse.ok(Map.of("barcode", goodsService.previewInStoreBarcode()));
    }
}
