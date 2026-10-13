package com.smms.common.task;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.smms.common.exception.BusinessException;
import com.smms.common.task.mapper.AsyncTaskMapper;
import com.smms.security.LoginUser;
import com.smms.sys.service.OperationLogService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 异步任务框架单测：提交、成功落盘、失败留痕、归属校验、过期与排队保护。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AsyncTaskServiceTest {

    @Mock
    private AsyncTaskMapper mapper;

    @Mock
    private OperationLogService operationLogService;

    @TempDir
    Path tempDir;

    private AsyncTaskService service;

    /** 同线程执行器：使提交与执行在测试中同步完成，便于断言 */
    private final Executor sameThread = Runnable::run;

    @BeforeEach
    void setUp() {
        // MyBatis-Plus 条件构造器需要实体元信息（单测无 Spring 上下文）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AsyncTask.class);
        TaskFileStore fileStore = new TaskFileStore(tempDir.toString());
        service = new AsyncTaskService(mapper, fileStore, new ObjectMapper(), sameThread, 24, operationLogService);
        loginAs(1L, "admin", false);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(Long userId, String username, boolean superAdmin) {
        List<String> roles = superAdmin ? List.of("SUPER_ADMIN") : List.of("CASHIER");
        LoginUser user = new LoginUser(userId, username, username, roles, roles,
                java.util.Set.of(), "fp", false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @Test
    @DisplayName("导出任务：提交后执行并落盘，任务转为成功并记录文件名与大小")
    void exportTaskSucceeds() throws Exception {
        byte[] payload = "xlsx-bytes".getBytes(StandardCharsets.UTF_8);

        AsyncTaskVO submitted = service.submitExport("SALES_REPORT_EXPORT", "销售报表导出",
                Map.of("view", "daily"), ctx -> {
                    ctx.progress(50, "正在生成数据");
                    ctx.fileName("sales-daily.xlsx");
                    return payload;
                });

        // 生产环境返回排队态；单测用同步执行器，故可能已直接完成
        assertThat(submitted.status()).isIn(AsyncTask.STATUS_QUEUED, AsyncTask.STATUS_SUCCESS);
        assertThat(submitted.taskNo()).hasSize(32);

        AsyncTask saved = captureInserted();
        assertThat(saved.getStatus()).isEqualTo(AsyncTask.STATUS_SUCCESS);
        assertThat(saved.getProgress()).isEqualTo(100);
        assertThat(saved.getFileName()).isEqualTo("sales-daily.xlsx");
        assertThat(saved.getFileSize()).isEqualTo(payload.length);
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getUsername()).isEqualTo("admin");
        assertThat(saved.getExpiresAt()).isAfter(LocalDateTime.now().plusHours(23));
        assertThat(Files.readAllBytes(Path.of(saved.getFilePath()))).isEqualTo(payload);

        // GAP-P1-06：任务完成写操作日志（提交动作已有 @OpLog，此处补执行结果）
        org.mockito.ArgumentCaptor<com.smms.sys.entity.OperationLog> logCaptor =
                org.mockito.ArgumentCaptor.forClass(com.smms.sys.entity.OperationLog.class);
        org.mockito.Mockito.verify(operationLogService).recordAsync(logCaptor.capture());
        com.smms.sys.entity.OperationLog doneLog = logCaptor.getValue();
        assertThat(doneLog.getModule()).isEqualTo("任务中心");
        assertThat(doneLog.getAction()).contains("完成");
        assertThat(doneLog.getResult()).isEqualTo(1);
        assertThat(doneLog.getTarget()).isEqualTo("sales-daily.xlsx");
    }

    @Test
    @DisplayName("导出任务：生产者抛异常时任务转为失败并保留原因，不落盘")
    void exportTaskFailureIsRecorded() {
        service.submitExport("SALES_REPORT_EXPORT", "销售报表导出", Map.of(),
                ctx -> {
                    throw new IllegalStateException("数据库连接中断");
                });

        AsyncTask saved = captureInserted();
        assertThat(saved.getStatus()).isEqualTo(AsyncTask.STATUS_FAILED);
        assertThat(saved.getErrorMessage()).contains("数据库连接中断");
        assertThat(saved.getFilePath()).isNull();
        assertThat(saved.getFinishTime()).isNotNull();

        // GAP-P1-06：任务失败同样写操作日志（此前只落 async_task，提交日志无法反映真实结果）
        org.mockito.ArgumentCaptor<com.smms.sys.entity.OperationLog> logCaptor =
                org.mockito.ArgumentCaptor.forClass(com.smms.sys.entity.OperationLog.class);
        org.mockito.Mockito.verify(operationLogService).recordAsync(logCaptor.capture());
        com.smms.sys.entity.OperationLog failLog = logCaptor.getValue();
        assertThat(failLog.getModule()).isEqualTo("任务中心");
        assertThat(failLog.getAction()).contains("失败");
        assertThat(failLog.getResult()).isEqualTo(0);
        assertThat(failLog.getErrorMsg()).contains("数据库连接中断");
    }

    @Test
    @DisplayName("导入任务：结果摘要写入 result_json，下载走导出通道")
    void importTaskStoresResult() throws Exception {
        MultipartFile upload = new MockMultipartFile("file", "m.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "stub".getBytes(StandardCharsets.UTF_8));
        service.submitImport("MEMBER_IMPORT", "会员导入提交", Map.of("file", "m.xlsx"),
                upload, ctx -> {
                    ctx.result(Map.of("successCount", 3, "failCount", 1));
                    return null;
                });

        AsyncTask saved = captureInserted();
        assertThat(saved.getStatus()).isEqualTo(AsyncTask.STATUS_SUCCESS);
        assertThat(saved.getResultJson()).contains("successCount");
        assertThat(saved.getTaskType()).isEqualTo(AsyncTask.TYPE_IMPORT);
    }

    @Test
    @DisplayName("归属校验：非提交人不可查看或下载他人任务")
    void otherUserCannotAccess() {
        AsyncTask task = task("a".repeat(32), AsyncTask.STATUS_SUCCESS, 2L, "cashier");
        task.setFilePath(tempDir.resolve("x.xlsx").toString());
        when(mapper.selectOne(any())).thenReturn(task);

        loginAs(1L, "admin", false);
        assertThatThrownBy(() -> service.get(task.getTaskNo()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅任务提交人");
        assertThatThrownBy(() -> service.download(task.getTaskNo()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅任务提交人");
    }

    @Test
    @DisplayName("任务不存在时返回 1901；未完成时下载提示稍后重试")
    void notFoundAndNotFinished() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.get("b".repeat(32)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("任务不存在");

        AsyncTask running = task("c".repeat(32), AsyncTask.STATUS_RUNNING, 1L, "admin");
        when(mapper.selectOne(any())).thenReturn(running);
        assertThatThrownBy(() -> service.download(running.getTaskNo()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚未完成");
    }

    @Test
    @DisplayName("过期任务：惰性标记失败并清理文件")
    void expiredTaskIsMarkedFailed() throws Exception {
        Path file = tempDir.resolve("expired.xlsx");
        Files.writeString(file, "stale");
        AsyncTask task = task("d".repeat(32), AsyncTask.STATUS_SUCCESS, 1L, "admin");
        task.setFilePath(file.toString());
        task.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(mapper.selectOne(any())).thenReturn(task);

        AsyncTaskVO vo = service.get(task.getTaskNo());

        assertThat(vo.status()).isEqualTo(AsyncTask.STATUS_FAILED);
        assertThat(vo.errorMessage()).contains("过期");
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    @DisplayName("排队保护：线程池拒绝时提交直接失败，不产生脏记录")
    void rejectedSubmissionFailsFast() {
        TaskFileStore fileStore = new TaskFileStore(tempDir.toString());
        Executor rejecting = command -> {
            throw new java.util.concurrent.RejectedExecutionException("queue full");
        };
        AsyncTaskService full = new AsyncTaskService(mapper, fileStore, new ObjectMapper(), rejecting, 24, operationLogService);

        assertThatThrownBy(() -> full.submitExport("GOODS_EXPORT", "商品导出", Map.of(), ctx -> new byte[0]))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("后台任务较多");
    }

    private AsyncTask task(String taskNo, String status, Long userId, String username) {
        AsyncTask task = new AsyncTask();
        task.setTaskNo(taskNo);
        task.setTaskType(AsyncTask.TYPE_EXPORT);
        task.setBizType("SALES_REPORT_EXPORT");
        task.setBizName("销售报表导出");
        task.setStatus(status);
        task.setProgress(100);
        task.setUserId(userId);
        task.setUsername(username);
        task.setCreateTime(LocalDateTime.now().minusMinutes(5));
        task.setExpiresAt(LocalDateTime.now().plusHours(24));
        return task;
    }

    /** 捕获 mapper.insert 传入的实体（服务在同一实例上就地更新状态） */
    private AsyncTask captureInserted() {
        org.mockito.ArgumentCaptor<AsyncTask> captor =
                org.mockito.ArgumentCaptor.forClass(AsyncTask.class);
        org.mockito.Mockito.verify(mapper).insert(captor.capture());
        return captor.getValue();
    }
}
