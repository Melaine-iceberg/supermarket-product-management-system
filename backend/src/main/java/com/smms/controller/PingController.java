package com.smms.controller;

import com.smms.common.api.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 连通性探针接口，用于验证统一响应结构与前后端联调。
 * 正式健康检查（含数据库状态）见 /actuator/health（IR-SW-08）。
 */
@RestController
@RequestMapping("/api/v1")
public class PingController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Value("${spring.application.name:smms-server}")
    private String applicationName;

    @GetMapping("/ping")
    public ApiResponse<Map<String, String>> ping() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("app", applicationName);
        body.put("serverTime", LocalDateTime.now().format(TS));
        return ApiResponse.ok(body);
    }
}
