package com.smms.common.task;

import com.smms.security.LoginUser;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务执行上下文：供 {@link TaskProducer} 读取提交参数、上报进度与产出。
 * 仅任务线程内使用。
 */
public class TaskContext {

    private final String taskNo;
    private final LoginUser user;
    private final Long userId;
    private final String username;
    private final Map<String, Object> params;
    private final Path uploadFile;
    private final AsyncTaskService service;
    private final AtomicInteger lastReported = new AtomicInteger(-1);

    private volatile String fileName;
    private volatile Object result;

    TaskContext(String taskNo, LoginUser user, Map<String, Object> params,
                Path uploadFile, AsyncTaskService service) {
        this.taskNo = taskNo;
        this.user = user;
        this.userId = user.userId();
        this.username = user.username();
        this.params = params;
        this.uploadFile = uploadFile;
        this.service = service;
    }

    public String taskNo() {
        return taskNo;
    }

    /**
     * 提交人身份。后台线程没有 SecurityContext，任务内部需要“当前操作人”时
     * 必须使用本方法（而非 {@code SecurityUtils.getLoginUser()}）。
     */
    public LoginUser user() {
        return user;
    }

    public Long userId() {
        return userId;
    }

    public String username() {
        return username;
    }

    /** 导入任务的上传文件（导出任务为 null） */
    public Path uploadFile() {
        return uploadFile;
    }

    public Object param(String key) {
        return params == null ? null : params.get(key);
    }

    public String paramStr(String key) {
        Object v = param(key);
        return v == null ? null : String.valueOf(v);
    }

    public Long paramLong(String key) {
        Object v = param(key);
        if (v == null || "".equals(v)) {
            return null;
        }
        return v instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(v).trim());
    }

    public Integer paramInt(String key) {
        Object v = param(key);
        if (v == null || "".equals(v)) {
            return null;
        }
        return v instanceof Number n ? n.intValue() : Integer.valueOf(String.valueOf(v).trim());
    }

    public LocalDate paramDate(String key) {
        String s = paramStr(key);
        return s == null || s.isBlank() ? null : LocalDate.parse(s.trim());
    }

    /** 上报进度（同一百分比只落库一次，避免无谓 UPDATE） */
    public void progress(int percent, String phase) {
        int pct = Math.max(0, Math.min(100, percent));
        if (lastReported.getAndSet(pct) == pct && phase == null) {
            return;
        }
        service.updateProgress(taskNo, pct, phase);
    }

    /** 指定下载文件名（导出任务必须设置） */
    public void fileName(String name) {
        this.fileName = name;
    }

    public String fileName() {
        return fileName;
    }

    /** 设置导入类任务的结果对象（序列化后写入 result_json） */
    public void result(Object value) {
        this.result = value;
    }

    public Object result() {
        return result;
    }
}
