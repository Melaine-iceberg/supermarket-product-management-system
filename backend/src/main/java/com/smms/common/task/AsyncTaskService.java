package com.smms.common.task;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import tools.jackson.databind.ObjectMapper;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.common.task.mapper.AsyncTaskMapper;
import com.smms.security.LoginUser;
import com.smms.security.SecurityUtils;
import com.smms.sys.entity.OperationLog;
import com.smms.sys.service.OperationLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 异步任务服务：提交、进度上报、查询、下载、失败兜底、启动恢复与过期清理。
 *
 * <p>存在意义：报表导出、大批量导入等重任务同步执行时会长时间占用请求连接，
 * 经反向代理（Cloudflare 约 100 秒源站超时）访问会返回 524。改为「提交任务 + 轮询进度 + 完成下载」后，
 * 提交接口在 1 秒内返回，长任务在后台线程池执行，结果文件保留 24 小时可重复下载。
 */
@Service
public class AsyncTaskService {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskService.class);

    private final AsyncTaskMapper mapper;
    private final TaskFileStore fileStore;
    private final ObjectMapper objectMapper;
    private final Executor taskExecutor;
    private final int retainHours;
    private final OperationLogService operationLogService;

    public AsyncTaskService(AsyncTaskMapper mapper,
                            TaskFileStore fileStore,
                            ObjectMapper objectMapper,
                            @Qualifier("taskExecutor") Executor taskExecutor,
                            @Value("${smms.task.retain-hours:24}") int retainHours,
                            OperationLogService operationLogService) {
        this.mapper = mapper;
        this.fileStore = fileStore;
        this.objectMapper = objectMapper;
        this.taskExecutor = taskExecutor;
        this.retainHours = retainHours;
        this.operationLogService = operationLogService;
    }

    // ------------------------------------------------------------------ 提交

    /** 提交导出任务：producer 返回 Excel 字节，需通过 {@link TaskContext#fileName(String)} 指定文件名 */
    public AsyncTaskVO submitExport(String bizType, String bizName, Map<String, Object> params, TaskProducer producer) {
        return submit(AsyncTask.TYPE_EXPORT, bizType, bizName, params, null, producer);
    }

    /** 提交导入任务：上传文件先落临时盘，producer 内通过 {@link TaskContext#uploadFile()} 读取 */
    public AsyncTaskVO submitImport(String bizType, String bizName, Map<String, Object> params,
                                    MultipartFile file, TaskProducer producer) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.TASK_UPLOAD_MISSING);
        }
        return submit(AsyncTask.TYPE_IMPORT, bizType, bizName, params, file, producer);
    }

    private AsyncTaskVO submit(String taskType, String bizType, String bizName, Map<String, Object> params,
                               MultipartFile file, TaskProducer producer) {
        LoginUser user = SecurityUtils.getLoginUser();
        List<SimpleGrantedAuthority> authorities = snapshotAuthorities(user);
        String taskNo = UUID.randomUUID().toString().replace("-", "");

        Path upload = null;
        if (file != null) {
            try {
                upload = fileStore.saveUpload(taskNo, file.getOriginalFilename(), file.getBytes());
            } catch (IOException e) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "上传文件读取失败，请重试");
            }
        }

        AsyncTask task = new AsyncTask();
        task.setTaskNo(taskNo);
        task.setTaskType(taskType);
        task.setBizType(bizType);
        task.setBizName(bizName);
        task.setStatus(AsyncTask.STATUS_QUEUED);
        task.setProgress(0);
        task.setPhase("已提交，等待执行");
        task.setParamsJson(toJsonQuietly(params));
        task.setUserId(user.userId());
        task.setUsername(user.username());
        task.setCreateTime(LocalDateTime.now());
        task.setExpiresAt(LocalDateTime.now().plusHours(retainHours));
        mapper.insert(task);

        try {
            AsyncTask submitted = task;
            Path uploadPath = upload;
            taskExecutor.execute(() -> run(submitted, user, authorities, params, uploadPath, producer));
        } catch (RejectedExecutionException e) {
            fileStore.deleteQuietly(upload == null ? null : upload.toString());
            markFailed(task, ErrorCode.TASK_QUEUE_FULL.getMessage());
            throw new BusinessException(ErrorCode.TASK_QUEUE_FULL);
        }
        return toVO(task);
    }

    // ------------------------------------------------------------------ 执行

    private void run(AsyncTask task, LoginUser user, List<SimpleGrantedAuthority> authorities,
                     Map<String, Object> params, Path upload, TaskProducer producer) {
        SecurityContextHolder.setContext(buildContext(user, authorities));
        String taskNo = task.getTaskNo();
        try {
            LocalDateTime startedAt = LocalDateTime.now();
            update(Wrappers.<AsyncTask>lambdaUpdate()
                    .eq(AsyncTask::getTaskNo, taskNo)
                    .set(AsyncTask::getStatus, AsyncTask.STATUS_RUNNING)
                    .set(AsyncTask::getStartTime, startedAt)
                    .set(AsyncTask::getProgress, 1)
                    .set(AsyncTask::getPhase, "开始执行"));
            task.setStatus(AsyncTask.STATUS_RUNNING);
            task.setStartTime(startedAt);
            task.setProgress(1);
            task.setPhase("开始执行");

            TaskContext ctx = new TaskContext(taskNo, user, params, upload, this);
            byte[] bytes = producer.produce(ctx);

            if (bytes != null) {
                String fileName = ctx.fileName() == null ? "导出结果.xlsx" : ctx.fileName();
                Path path = fileStore.save(taskNo, fileName, bytes);
                update(Wrappers.<AsyncTask>lambdaUpdate()
                        .eq(AsyncTask::getTaskNo, taskNo)
                        .set(AsyncTask::getStatus, AsyncTask.STATUS_SUCCESS)
                        .set(AsyncTask::getProgress, 100)
                        .set(AsyncTask::getPhase, "已完成")
                        .set(AsyncTask::getFileName, fileName)
                        .set(AsyncTask::getFilePath, path.toString())
                        .set(AsyncTask::getFileSize, (long) bytes.length)
                        .set(AsyncTask::getFinishTime, LocalDateTime.now()));
                task.setStatus(AsyncTask.STATUS_SUCCESS);
                task.setProgress(100);
                task.setPhase("已完成");
                task.setFileName(fileName);
                task.setFilePath(path.toString());
                task.setFileSize((long) bytes.length);
                task.setFinishTime(LocalDateTime.now());
            } else {
                update(Wrappers.<AsyncTask>lambdaUpdate()
                        .eq(AsyncTask::getTaskNo, taskNo)
                        .set(AsyncTask::getStatus, AsyncTask.STATUS_SUCCESS)
                        .set(AsyncTask::getProgress, 100)
                        .set(AsyncTask::getPhase, "已完成")
                        .set(AsyncTask::getResultJson, toJsonQuietly(ctx.result()))
                        .set(AsyncTask::getFinishTime, LocalDateTime.now()));
                task.setStatus(AsyncTask.STATUS_SUCCESS);
                task.setProgress(100);
                task.setPhase("已完成");
                task.setResultJson(toJsonQuietly(ctx.result()));
                task.setFinishTime(LocalDateTime.now());
            }
            log.info("异步任务完成：taskNo={}, bizType={}, type={}", taskNo, task.getBizType(), task.getTaskType());
            logResult(task, user, true, null, startedAt);
        } catch (Exception e) {
            log.error("异步任务失败：taskNo={}, bizType={}", taskNo, task.getBizType(), e);
            markFailed(task, friendlyMessage(e));
            // GAP-P1-06：任务失败同样写入操作日志（此前只落 async_task，提交日志无法反映真实结果）
            logResult(task, user, false, task.getErrorMessage(), task.getStartTime());
        } finally {
            fileStore.deleteQuietly(upload == null ? null : upload.toString());
            SecurityContextHolder.clearContext();
        }
    }

    /** 任务完成/失败写操作日志（FR-RPT-08/GAP-P1-06）：提交动作已有 @OpLog，此处补真实执行结果 */
    private void logResult(AsyncTask task, LoginUser user, boolean success, String errorMsg, LocalDateTime startedAt) {
        try {
            OperationLog entity = new OperationLog();
            if (user != null) {
                entity.setUserId(user.userId());
                entity.setUsername(user.username());
                entity.setRealName(user.realName());
                entity.setRoleNames(user.roleNames() == null ? null : String.join(",", user.roleNames()));
            }
            boolean export = AsyncTask.TYPE_EXPORT.equals(task.getTaskType());
            entity.setModule("任务中心");
            entity.setAction((export ? "导出" : "导入") + (success ? "完成" : "失败"));
            entity.setTarget(task.getFileName() != null ? task.getFileName() : task.getTaskNo());
            entity.setParamSummary(task.getBizName() + "（" + task.getTaskNo() + "）");
            entity.setResult(success ? 1 : 0);
            entity.setErrorMsg(errorMsg);
            if (startedAt != null) {
                entity.setCostMs((int) Math.min(Integer.MAX_VALUE,
                        Math.max(0, Duration.between(startedAt, LocalDateTime.now()).toMillis())));
            }
            entity.setOperateTime(LocalDateTime.now());
            operationLogService.recordAsync(entity);
        } catch (Exception e) {
            log.warn("任务结果操作日志记录失败：taskNo={}, {}", task.getTaskNo(), e.getMessage());
        }
    }

    /** 快照当前用户权限：后台线程需要重新注入 SecurityContext，否则脱敏等鉴权分支会静默降级 */
    private List<SimpleGrantedAuthority> snapshotAuthorities(LoginUser user) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        if (user.roleCodes() != null) {
            user.roleCodes().forEach(code -> authorities.add(new SimpleGrantedAuthority("ROLE_" + code)));
        }
        if (user.permCodes() != null) {
            user.permCodes().forEach(code -> authorities.add(new SimpleGrantedAuthority(code)));
        }
        return authorities;
    }

    private SecurityContext buildContext(LoginUser user, List<SimpleGrantedAuthority> authorities) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, authorities));
        return context;
    }

    private String friendlyMessage(Exception e) {
        String message = e instanceof BusinessException ? e.getMessage() : "任务执行失败：" + e.getMessage();
        if (message == null || message.isBlank()) {
            message = "任务执行失败，请重试或联系管理员";
        }
        return message.length() > 400 ? message.substring(0, 400) : message;
    }

    private void markFailed(AsyncTask task, String message) {
        LocalDateTime finishedAt = LocalDateTime.now();
        update(Wrappers.<AsyncTask>lambdaUpdate()
                .eq(AsyncTask::getTaskNo, task.getTaskNo())
                .set(AsyncTask::getStatus, AsyncTask.STATUS_FAILED)
                .set(AsyncTask::getPhase, "执行失败")
                .set(AsyncTask::getErrorMessage, message)
                .set(AsyncTask::getFinishTime, finishedAt));
        task.setStatus(AsyncTask.STATUS_FAILED);
        task.setPhase("执行失败");
        task.setErrorMessage(message);
        task.setFinishTime(finishedAt);
    }

    // ------------------------------------------------------------------ 查询与下载

    /** 任务详情（含归属校验：仅提交人或超级管理员可查看） */
    public AsyncTaskVO get(String taskNo) {
        AsyncTask task = requireOwned(taskNo);
        expireIfNeeded(task);
        return toVO(task);
    }

    /** 任务结果（含归属、状态、文件有效性校验） */
    public TaskDownload download(String taskNo) {
        AsyncTask task = requireOwned(taskNo);
        expireIfNeeded(task);
        if (AsyncTask.STATUS_FAILED.equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.TASK_FILE_MISSING);
        }
        if (!AsyncTask.STATUS_SUCCESS.equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.TASK_NOT_FINISHED);
        }
        if (task.getFilePath() == null || !Files.exists(Path.of(task.getFilePath()))) {
            throw new BusinessException(ErrorCode.TASK_FILE_MISSING);
        }
        Path path = Path.of(task.getFilePath());
        String fileName = task.getFileName() == null ? task.getTaskNo() + ".xlsx" : task.getFileName();
        return new TaskDownload(path, fileName, contentTypeOf(fileName), task.getFileSize());
    }

    /** 依据扩展名推断下载响应类型 */
    private String contentTypeOf(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".csv")) {
            return "text/csv;charset=UTF-8";
        }
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    private AsyncTask requireOwned(String taskNo) {
        AsyncTask task = mapper.selectOne(Wrappers.<AsyncTask>lambdaQuery().eq(AsyncTask::getTaskNo, taskNo));
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        }
        LoginUser user = SecurityUtils.getLoginUser();
        if (!task.getUserId().equals(user.userId()) && !SecurityUtils.isSuperAdmin()) {
            throw new BusinessException(ErrorCode.TASK_NOT_OWNER);
        }
        return task;
    }

    /** 惰性过期：结果保留期已过则标记失败并删除文件 */
    private void expireIfNeeded(AsyncTask task) {
        if (task.getExpiresAt() != null && task.getExpiresAt().isBefore(LocalDateTime.now())
                && !AsyncTask.STATUS_FAILED.equals(task.getStatus())) {
            fileStore.deleteQuietly(task.getFilePath());
            markFailed(task, ErrorCode.TASK_FILE_MISSING.getMessage());
            task.setStatus(AsyncTask.STATUS_FAILED);
            task.setErrorMessage(ErrorCode.TASK_FILE_MISSING.getMessage());
            task.setFilePath(null);
        }
    }

    private AsyncTaskVO toVO(AsyncTask task) {
        Object result = null;
        if (task.getResultJson() != null && !task.getResultJson().isBlank()) {
            try {
                result = objectMapper.readTree(task.getResultJson());
            } catch (Exception e) {
                log.warn("任务结果解析失败：taskNo={}", task.getTaskNo(), e);
            }
        }
        return new AsyncTaskVO(
                task.getTaskNo(), task.getTaskType(), task.getBizType(), task.getBizName(),
                task.getStatus(), task.getProgress(), task.getPhase(),
                task.getFileName(), task.getFileSize(), task.getErrorMessage(), result,
                task.getCreateTime(), task.getFinishTime(), task.getExpiresAt());
    }

    private void update(LambdaUpdateWrapper<AsyncTask> wrapper) {
        mapper.update(null, wrapper);
    }

    /** 进度上报入口（供 {@link TaskContext} 调用） */
    void updateProgress(String taskNo, int percent, String phase) {
        update(Wrappers.<AsyncTask>lambdaUpdate()
                .eq(AsyncTask::getTaskNo, taskNo)
                .eq(AsyncTask::getStatus, AsyncTask.STATUS_RUNNING)
                .set(AsyncTask::getProgress, percent)
                .set(phase == null, AsyncTask::getPhase, null)
                .set(phase != null, AsyncTask::getPhase, phase));
    }

    private String toJsonQuietly(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("任务参数序列化失败", e);
            return null;
        }
    }

    // ------------------------------------------------------------------ 启动恢复与清理

    /** 服务重启后，未完成的任务不可能再被恢复，统一标记失败并提示重试 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedTasks() {
        try {
            int affected = mapper.update(null, Wrappers.<AsyncTask>lambdaUpdate()
                    .in(AsyncTask::getStatus, AsyncTask.STATUS_QUEUED, AsyncTask.STATUS_RUNNING)
                    .set(AsyncTask::getStatus, AsyncTask.STATUS_FAILED)
                    .set(AsyncTask::getPhase, "已中断")
                    .set(AsyncTask::getErrorMessage, "服务重启导致任务中断，请重新提交")
                    .set(AsyncTask::getFinishTime, LocalDateTime.now()));
            if (affected > 0) {
                log.warn("检测到 {} 个未完成的异步任务，已标记为失败", affected);
            }
        } catch (Exception e) {
            // 表未建（如未执行 db/upgrade-1.1-async-task.sql）时不得阻断启动，仅告警
            log.error("异步任务启动恢复失败，请确认已执行 db/upgrade-1.1-async-task.sql：{}", e.getMessage());
        }
    }

    /**
     * 过期任务清理（IR-SW-06）：删除过期 async_task 记录与结果文件（保留期默认 24 小时），返回清理总数。
     * 幂等：按 expires_at 条件删除；调度由 DynamicTaskScheduler 统一注册（GAP-P1-03）。
     */
    public int cleanExpiredTasks() {
        LocalDateTime now = LocalDateTime.now();
        List<AsyncTask> expired = mapper.selectList(Wrappers.<AsyncTask>lambdaQuery()
                .lt(AsyncTask::getExpiresAt, now)
                .isNotNull(AsyncTask::getFilePath));
        expired.forEach(task -> fileStore.deleteQuietly(task.getFilePath()));
        int deleted = mapper.delete(Wrappers.<AsyncTask>lambdaQuery().lt(AsyncTask::getExpiresAt, now));
        int orphan = fileStore.deleteOlderThan(Duration.ofHours(retainHours));
        if (deleted > 0 || orphan > 0) {
            log.info("异步任务清理：删除记录 {} 条、遗留文件 {} 个", deleted, orphan);
        }
        return deleted + orphan;
    }
}
