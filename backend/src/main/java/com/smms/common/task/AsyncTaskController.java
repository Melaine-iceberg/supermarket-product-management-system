package com.smms.common.task;

import com.smms.common.api.ApiResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * 异步任务查询与结果下载（2026-10 新增）。
 *
 * <p>重导出/导入提交改为后台执行后，前端通过本控制器轮询进度并下载结果文件，
 * 从而规避反向代理（Cloudflare 约 100s）源站超时导致的 524。</p>
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class AsyncTaskController {

    private final AsyncTaskService taskService;

    public AsyncTaskController(AsyncTaskService taskService) {
        this.taskService = taskService;
    }

    /** 查询任务状态（仅提交人本人可见） */
    @GetMapping("/{taskNo}")
    public ApiResponse<AsyncTaskVO> status(@PathVariable String taskNo) {
        return ApiResponse.ok(taskService.get(taskNo));
    }

    /** 下载任务结果文件（仅提交人本人、任务成功后可用） */
    @GetMapping("/{taskNo}/download")
    public ResponseEntity<Resource> download(@PathVariable String taskNo) {
        TaskDownload download = taskService.download(taskNo);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(download.contentType()));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(download.fileName(), StandardCharsets.UTF_8).build());
        if (download.size() != null) {
            headers.setContentLength(download.size());
        }
        // 结果文件按任务编号唯一命名：禁止中间代理缓存，避免越权后的陈旧内容
        headers.setCacheControl("private, no-store");
        return ResponseEntity.ok().headers(headers).body(new FileSystemResource(download.path()));
    }
}
