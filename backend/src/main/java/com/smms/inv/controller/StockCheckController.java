package com.smms.inv.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.common.api.ErrorCode;
import com.smms.common.api.PageVO;
import com.smms.common.exception.BusinessException;
import com.smms.inv.dto.CheckAuditRequest;
import com.smms.inv.dto.CheckCreateRequest;
import com.smms.inv.dto.CheckEntryRequest;
import com.smms.inv.dto.CheckImportVO;
import com.smms.inv.dto.CheckOrderDetailVO;
import com.smms.inv.dto.CheckOrderVO;
import com.smms.inv.dto.CheckQuery;
import com.smms.inv.dto.CheckSummaryVO;
import com.smms.inv.service.StockCheckExcelService;
import com.smms.inv.service.StockCheckService;
import com.smms.security.SecurityUtils;
import com.smms.sys.service.SensitiveAuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

/**
 * 盘点管理接口（FR-INV-07~09，§2.2.4 状态机）：
 * 创建（快照账面数）→ 录入实盘数 → 提交审核 → 店长审核（覆盖结存生成盘盈/亏流水）。
 * 审核人与盘点人必须不同（BR-SYS-01）；同范围不可重复创建未审核单（BR-INV-09）。
 */
@RestController
@RequestMapping("/api/v1/stock-checks")
@RequiredArgsConstructor
public class StockCheckController {

    private static final MediaType XLSX =
            new MediaType("application", "vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final StockCheckService stockCheckService;
    private final StockCheckExcelService checkExcelService;
    private final SensitiveAuthService sensitiveAuthService;

    /** 创建盘点单（FR-INV-07）：选择范围并快照账面数量，返回单号供跳转录入页 */
    @PostMapping
    @RequirePerm("sys:check:create")
    @OpLog(module = "库存管理", action = "创建盘点单")
    public ApiResponse<CheckOrderVO> create(@Valid @RequestBody CheckCreateRequest req) {
        return ApiResponse.ok(stockCheckService.create(req, SecurityUtils.getLoginUser().username()));
    }

    /** 盘点单分页（FR-INV-07）：单号/状态/盘点人/审核人/时间区间 */
    @GetMapping
    @RequirePerm("sys:check:list")
    public ApiResponse<PageVO<CheckOrderVO>> page(
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String checkerName,
            @RequestParam(required = false) String auditorName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        CheckQuery query = new CheckQuery(orderNo, status, checkerName, auditorName,
                beginDate, endDate, page, size);
        return ApiResponse.ok(stockCheckService.page(query));
    }

    /** 盘点单详情（FR-INV-07/08）：单头 + 明细分页，keyword 匹配编码/条码/名称（扫码定位行） */
    @GetMapping("/{id}")
    @RequirePerm("sys:check:list")
    public ApiResponse<CheckOrderDetailVO> detail(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean onlyDiff,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(stockCheckService.detail(id, page, size, onlyDiff, keyword));
    }

    /** 作废盘点单（§2.2.4：仅盘点中可作废），须填写原因 */
    @PostMapping("/{id}/cancel")
    @RequirePerm("sys:check:cancel")
    @OpLog(module = "库存管理", action = "作废盘点单")
    public ApiResponse<Void> cancel(@PathVariable Long id, @RequestBody Map<String, String> body) {
        stockCheckService.cancel(id, body.get("reason"));
        return ApiResponse.ok(null);
    }

    // ==================== 实盘数量录入（FR-INV-08） ====================

    /** 录入实盘数量（FR-INV-08）：逐行/批量录入，差异由服务端计算，差异行原因必填；返回实时汇总 */
    @PutMapping("/{id}/items")
    @RequirePerm("sys:check:update")
    @OpLog(module = "库存管理", action = "录入盘点实盘数")
    public ApiResponse<CheckSummaryVO> enter(@PathVariable Long id,
                                             @Valid @RequestBody CheckEntryRequest req) {
        return ApiResponse.ok(stockCheckService.enter(id, req));
    }

    /** 提交审核（FR-INV-08）：未盘行按账面确认，状态→待审核（§2.2.4） */
    @PostMapping("/{id}/submit")
    @RequirePerm("sys:check:submit")
    @OpLog(module = "库存管理", action = "提交盘点审核")
    public ApiResponse<CheckSummaryVO> submit(@PathVariable Long id) {
        return ApiResponse.ok(stockCheckService.submit(id));
    }

    /** 实盘录入模板下载（FR-INV-08）：按盘点单明细导出，实盘数量/差异原因留空线下填写 */
    @GetMapping("/{id}/items/template")
    @RequirePerm("sys:check:update")
    public ResponseEntity<byte[]> template(@PathVariable Long id) {
        return xlsx(checkExcelService.template(id), "盘点录入-" + id + ".xlsx");
    }

    /** Excel 批量导入实盘数（FR-INV-08）：编码/条码定位行，复用统一录入入口 */
    @PostMapping("/{id}/items/import")
    @RequirePerm("sys:check:update")
    @OpLog(module = "库存管理", action = "导入盘点实盘数")
    public ApiResponse<CheckImportVO> importActual(@PathVariable Long id,
                                                   @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(checkExcelService.importActual(id, open(file)));
    }

    // ==================== 盘点审核与库存调整（FR-INV-09） ====================

    /**
     * 盘点审核（FR-INV-09）：通过 → 单事务生成盘盈/盘亏流水并覆盖结存（BR-INV-08 不变式保持）；
     * 驳回 → 意见必填并退回盘点中。审核人与盘点人必须不同（BR-SYS-01）。
     */
    @PostMapping("/{id}/audit")
    @RequirePerm("sys:check:audit")
    @OpLog(module = "库存管理", action = "盘点审核")
    public ApiResponse<CheckOrderVO> audit(@PathVariable Long id,
                                           @Valid @RequestBody CheckAuditRequest req) {
        sensitiveAuthService.confirmPassword(req.password());
        return ApiResponse.ok(stockCheckService.audit(id, req, SecurityUtils.getLoginUser().username()));
    }

    private InputStream open(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请上传导入文件");
        }
        try {
            return file.getInputStream();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "读取上传文件失败");
        }
    }

    private ResponseEntity<byte[]> xlsx(byte[] body, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(XLSX);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
