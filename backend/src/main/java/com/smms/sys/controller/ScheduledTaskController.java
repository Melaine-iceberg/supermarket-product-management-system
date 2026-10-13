package com.smms.sys.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.common.api.PageVO;
import com.smms.common.sched.ScheduledTaskLogVO;
import com.smms.common.sched.ScheduledTaskService;
import com.smms.common.sched.ScheduledTaskVO;
import com.smms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 定时任务管理（IR-SW-06 / GAP-P1-03）：
 * 任务一览（含最近执行结果）、执行日志检索、手工触发补跑。
 */
@RestController
@RequestMapping("/api/v1/system/scheduled-tasks")
@RequiredArgsConstructor
public class ScheduledTaskController {

    private final ScheduledTaskService taskService;

    /** 任务一览：定义 + cron + 最近一次执行结果与耗时 */
    @GetMapping
    @RequirePerm("sys:sched:list")
    public ApiResponse<List<ScheduledTaskVO>> overview() {
        return ApiResponse.ok(taskService.overview());
    }

    /** 执行日志：按任务/结果过滤，分页倒序 */
    @GetMapping("/logs")
    @RequirePerm("sys:sched:list")
    public ApiResponse<PageVO<ScheduledTaskLogVO>> logs(
            @RequestParam(required = false) String taskCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(taskService.logPage(taskCode, status, page, size));
    }

    /** 手工触发补跑（NFR-REL-07）：任务幂等，重跑不产生重复数据 */
    @PostMapping("/{code}/run")
    @RequirePerm("sys:sched:run")
    @OpLog(module = "系统管理", action = "手工触发定时任务", target = "#code")
    public ApiResponse<ScheduledTaskLogVO> run(@PathVariable String code) {
        return ApiResponse.ok(taskService.run(code, "MANUAL", SecurityUtils.getLoginUser().username()));
    }
}
