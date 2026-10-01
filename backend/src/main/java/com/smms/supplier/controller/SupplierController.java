package com.smms.supplier.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.common.api.PageVO;
import com.smms.security.SecurityUtils;
import com.smms.supplier.dto.SupplierGoodsSaveRequest;
import com.smms.supplier.dto.SupplierGoodsVO;
import com.smms.supplier.dto.SupplierListVO;
import com.smms.supplier.dto.SupplierQuery;
import com.smms.supplier.dto.SupplierSaveRequest;
import com.smms.supplier.dto.SupplierStatusRequest;
import com.smms.supplier.service.SupplierService;
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
 * 供应商档案接口（FR-SUP-01~04）：
 * 新增（编码自动生成）、编辑、停用/启用、多维分页查询（含在途/未结算/最近合作统计）、
 * 供货商品维护（默认供应商唯一）。
 */
@RestController
@RequestMapping("/api/v1/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService supplierService;

    /** 列表多维检索（FR-SUP-02）：名称/编码/联系人/电话/状态/供货品类筛选 */
    @GetMapping
    @RequirePerm("sys:sup:list")
    public ApiResponse<PageVO<SupplierListVO>> page(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String contactPerson,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        SupplierQuery query = new SupplierQuery(name, code, contactPerson, phone,
                status, categoryId, page, size);
        return ApiResponse.ok(supplierService.page(query));
    }

    /** 供应商详情（FR-SUP-02）：档案 + 在途/未结算/最近合作等业务统计 */
    @GetMapping("/{id}")
    @RequirePerm("sys:sup:list")
    public ApiResponse<SupplierListVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(supplierService.detail(id));
    }

    /** 新增供应商（FR-SUP-01）：编码 S+5位流水自动生成（BR-SUP-03），名称唯一 */
    @PostMapping
    @RequirePerm("sys:sup:create")
    @OpLog(module = "供应商管理", action = "新增供应商", target = "#req.name")
    public ApiResponse<SupplierListVO> create(@Valid @RequestBody SupplierSaveRequest req) {
        return ApiResponse.ok(supplierService.create(req, SecurityUtils.getLoginUser().realName()));
    }

    /** 编辑供应商（FR-SUP-01）：编码不可改；银行账号/纳税人识别号脱敏提交保留原值（BR-SUP-01） */
    @PutMapping("/{id}")
    @RequirePerm("sys:sup:update")
    @OpLog(module = "供应商管理", action = "编辑供应商", target = "#id")
    public ApiResponse<Void> update(@PathVariable Long id,
                                    @Valid @RequestBody SupplierSaveRequest req) {
        supplierService.update(id, req, SecurityUtils.getLoginUser().realName());
        return ApiResponse.ok();
    }

    /** 停用/启用（FR-SUP-03）：停用后不可被新采购单选择，历史单据与结算记录保留可查 */
    @PutMapping("/{id}/status")
    @RequirePerm("sys:sup:status")
    @OpLog(module = "供应商管理", action = "停用启用供应商", target = "#id")
    public ApiResponse<Void> updateStatus(@PathVariable Long id,
                                          @Valid @RequestBody SupplierStatusRequest req) {
        supplierService.changeStatus(id, req.status());
        return ApiResponse.ok();
    }

    /** 供货商品清单（FR-SUP-04）：含商品编码/名称/规格/分类/单位与默认标记 */
    @GetMapping("/{id}/goods")
    @RequirePerm("sys:sup:list")
    public ApiResponse<List<SupplierGoodsVO>> listGoods(@PathVariable Long id) {
        return ApiResponse.ok(supplierService.listGoods(id));
    }

    /** 维护供货商品（FR-SUP-04）：全量覆盖；一商品至多一个默认供应商 */
    @PutMapping("/{id}/goods")
    @RequirePerm("sys:sup:goods")
    @OpLog(module = "供应商管理", action = "维护供货商品", target = "#id")
    public ApiResponse<Void> saveGoods(@PathVariable Long id,
                                       @Valid @RequestBody SupplierGoodsSaveRequest req) {
        supplierService.saveGoods(id, req, SecurityUtils.getLoginUser().realName());
        return ApiResponse.ok();
    }
}
