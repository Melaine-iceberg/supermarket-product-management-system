package com.smms.sys.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.common.api.PageVO;
import com.smms.sys.entity.OperationLog;
import com.smms.sys.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * 操作日志接口（FR-SYS-08）：查询与导出；日志只增不改不删。
 */
@RestController
@RequestMapping("/api/v1/oplogs")
@RequiredArgsConstructor
public class OperationLogController {

    private final OperationLogService operationLogService;

    @GetMapping
    @RequirePerm("sys:oplog:list")
    public ApiResponse<PageVO<OperationLog>> page(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) Integer result,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(operationLogService.page(username, module, result,
                beginDate, endDate, page, Math.min(size, 500)));
    }

    /** 模块下拉选项 */
    @GetMapping("/modules")
    @RequirePerm("sys:oplog:list")
    public ApiResponse<List<String>> modules() {
        return ApiResponse.ok(operationLogService.listModules());
    }

    /** 导出 CSV（UTF-8 BOM，导出行为记录日志，FR-SYS-08） */
    @GetMapping("/export")
    @RequirePerm("sys:oplog:export")
    @OpLog(module = "系统管理", action = "导出操作日志")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) Integer result,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        String csv = operationLogService.exportCsv(username, module, result, beginDate, endDate);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("operation-logs.csv", StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(csv.getBytes(StandardCharsets.UTF_8));
    }
}
