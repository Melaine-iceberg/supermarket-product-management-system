package com.smms.member.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.common.task.AsyncTaskService;
import com.smms.common.task.AsyncTaskVO;
import com.smms.common.task.TaskParams;
import com.smms.member.dto.MemberImportReportVO;
import com.smms.member.dto.MemberImportResultVO;
import com.smms.member.service.MemberExcelService;
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
import java.nio.file.Files;

/**
 * 会员 Excel 批量导入接口（FR-MEM-03，复用 FR-GOODS-11 机制）：
 * 模板下载、上传全量校验（不写库）、确认提交（单事务部分提交，BR-GOODS-07）。
 */
@RestController
@RequestMapping("/api/v1/members/import")
@RequiredArgsConstructor
public class MemberExcelController {

    private static final MediaType XLSX =
            new MediaType("application", "vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final MemberExcelService memberExcelService;
    private final AsyncTaskService taskService;

    /** 模板下载（FR-MEM-03） */
    @GetMapping("/template")
    @RequirePerm("sys:mem:import")
    @OpLog(module = "会员管理", action = "下载会员导入模板")
    public ResponseEntity<byte[]> template() {
        return xlsx(memberExcelService.template(), "member-import-template.xlsx");
    }

    /** 上传校验：全量校验生成报告，不写库（BR-GOODS-07） */
    @PostMapping("/validate")
    @RequirePerm("sys:mem:import")
    @OpLog(module = "会员管理", action = "校验会员导入文件")
    public ApiResponse<MemberImportReportVO> validate(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(memberExcelService.validate(open(file)));
    }

    /** 确认提交：单事务内仅导入校验通过的行，失败行返回明细供修正重传 */
    @PostMapping("/commit")
    @RequirePerm("sys:mem:import")
    @OpLog(module = "会员管理", action = "提交会员导入")
    public ApiResponse<AsyncTaskVO> commit(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(taskService.submitImport("MEMBER_IMPORT_COMMIT", "会员导入", TaskParams.of(),
                file, ctx -> {
                    ctx.progress(20, "正在校验导入文件");
                    String operator = ctx.user().realName();
                    ctx.progress(40, "正在写入会员数据");
                    try (InputStream in = Files.newInputStream(ctx.uploadFile())) {
                        MemberImportResultVO result = memberExcelService.commit(in, operator);
                        ctx.result(result);
                    }
                    return null;
                }));
    }

    /** 上传文件读取流（校验阶段同步执行，请求流仍有效） */
    private InputStream open(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.TASK_UPLOAD_MISSING, "请上传导入文件");
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
