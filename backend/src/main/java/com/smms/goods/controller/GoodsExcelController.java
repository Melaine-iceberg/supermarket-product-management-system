package com.smms.goods.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.goods.dto.GoodsImportReportVO;
import com.smms.goods.dto.GoodsImportResultVO;
import com.smms.goods.dto.GoodsQuery;
import com.smms.goods.service.GoodsExcelService;
import com.smms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * 商品 Excel 导入导出接口（FR-GOODS-11/12）：
 * 模板下载、上传全量校验（不写库）、确认提交（单事务部分提交，BR-GOODS-07）、
 * 按当前筛选导出（字段可选，导出记录日志）。
 */
@RestController
@RequestMapping("/api/v1/goods")
@RequiredArgsConstructor
public class GoodsExcelController {

    private static final MediaType XLSX =
            new MediaType("application", "vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final GoodsExcelService goodsExcelService;

    /** 模板下载（FR-GOODS-11） */
    @GetMapping("/import/template")
    @RequirePerm("sys:goods:import")
    @OpLog(module = "商品管理", action = "下载导入模板")
    public ResponseEntity<byte[]> template() {
        return xlsx(goodsExcelService.template(), "goods-import-template.xlsx");
    }

    /** 上传校验：全量校验生成报告，不写库（BR-GOODS-07） */
    @PostMapping("/import/validate")
    @RequirePerm("sys:goods:import")
    @OpLog(module = "商品管理", action = "校验导入文件")
    public ApiResponse<GoodsImportReportVO> validate(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(goodsExcelService.validate(open(file)));
    }

    /** 确认提交：单事务内仅导入校验通过的行，失败行返回明细供修正重传 */
    @PostMapping("/import/commit")
    @RequirePerm("sys:goods:import")
    @OpLog(module = "商品管理", action = "提交商品导入")
    public ApiResponse<GoodsImportResultVO> commit(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(goodsExcelService.commit(open(file), SecurityUtils.getLoginUser().username()));
    }

    /** 失败行导出：重新上传同一文件，返回仅含失败行的 xlsx（末列附失败原因，修正后可直接重传） */
    @PostMapping("/import/failed-export")
    @RequirePerm("sys:goods:import")
    @OpLog(module = "商品管理", action = "导出导入失败行")
    public ResponseEntity<byte[]> failedExport(@RequestParam("file") MultipartFile file) {
        return xlsx(goodsExcelService.failedRowsExport(open(file)), "goods-import-failed.xlsx");
    }

    /** 清单导出（FR-GOODS-12）：与列表同一筛选条件；fields 逗号分隔，空为全部字段 */
    @GetMapping("/export")
    @RequirePerm("sys:goods:export")
    @OpLog(module = "商品管理", action = "导出商品清单")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String goodsCode,
            @RequestParam(required = false) String barcode,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String stockStatus,
            @RequestParam(required = false) Integer isWeighed,
            @RequestParam(required = false) Integer allowReturn,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir,
            @RequestParam(required = false) String fields) {
        GoodsQuery query = new GoodsQuery(name, goodsCode, barcode, categoryId, brandId,
                status, stockStatus, isWeighed, allowReturn, 1, Integer.MAX_VALUE, sortBy, sortDir);
        List<String> fieldList = fields == null || fields.isBlank()
                ? List.of() : Arrays.stream(fields.split(",")).map(String::trim).toList();
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return xlsx(goodsExcelService.export(query, fieldList), "goods-export-" + ts + ".xlsx");
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
