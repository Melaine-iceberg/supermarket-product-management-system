package com.smms.common.task;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 异步任务（导出/导入）：重任务改为后台执行，规避反向代理源站超时（Cloudflare 约 100s → 524）。
 * 状态流转：QUEUED → RUNNING → SUCCESS / FAILED。
 */
@Data
@TableName("async_task")
public class AsyncTask {

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    public static final String TYPE_EXPORT = "EXPORT";
    public static final String TYPE_IMPORT = "IMPORT";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对外唯一标识 */
    private String taskNo;

    /** EXPORT / IMPORT */
    private String taskType;

    /** 业务类型，如 SALES_REPORT_EXPORT */
    private String bizType;

    /** 任务名称（前端展示） */
    private String bizName;

    private String status;

    /** 进度百分比 0~100 */
    private Integer progress;

    /** 当前阶段描述 */
    private String phase;

    private String fileName;

    private String filePath;

    private Long fileSize;

    /** 导入类任务结果摘要（JSON） */
    private String resultJson;

    private String errorMessage;

    /** 提交参数快照（JSON） */
    private String paramsJson;

    private Long userId;

    private String username;

    private LocalDateTime createTime;

    private LocalDateTime startTime;

    private LocalDateTime finishTime;

    /** 结果保留期到期时间 */
    private LocalDateTime expiresAt;
}
