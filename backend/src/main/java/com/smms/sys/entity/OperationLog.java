package com.smms.sys.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志（DR-33，FR-SYS-08）：只增不改不删，保留 ≥ 1 年。
 */
@Data
@TableName("operation_log")
public class OperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String username;

    /** 操作人真实姓名（BR-SYS-07：关键业务动作同时记录账号与真实姓名，GAP-P1-10） */
    private String realName;

    /** 操作人角色（记录角色名） */
    private String roleNames;

    /** 模块（如 商品管理、安全审计） */
    private String module;

    /** 动作（如 新增用户、越权访问） */
    private String action;

    /** 目标对象标识（单号/编码等） */
    private String target;

    private String httpMethod;

    private String requestUrl;

    /** 请求参数摘要（不含敏感信息） */
    private String paramSummary;

    private String ip;

    private String userAgent;

    /** 1 成功 0 失败 */
    private Integer result;

    private String errorMsg;

    /** 耗时（毫秒） */
    private Integer costMs;

    private LocalDateTime operateTime;
}
