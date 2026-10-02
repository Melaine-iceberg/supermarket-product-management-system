package com.smms.sys.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.security.SecurityUtils;
import com.smms.sys.dto.ConfigUpdateRequest;
import com.smms.sys.dto.PrintInfoVO;
import com.smms.sys.entity.SysConfig;
import com.smms.sys.service.SysConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 系统参数接口（FR-SYS-06）：分组展示，修改需权限并留痕。
 */
@RestController
@RequestMapping("/api/v1/configs")
@RequiredArgsConstructor
public class SysConfigController {

    private final SysConfigService configService;

    @GetMapping
    @RequirePerm("sys:config:list")
    public ApiResponse<List<SysConfig>> list() {
        return ApiResponse.ok(configService.listAll());
    }

    /** 打印页眉参数（IR-UI-08）：仅暴露店名/地址/电话/纸宽，任意已登录用户可读 */
    @GetMapping("/print-info")
    public ApiResponse<PrintInfoVO> printInfo() {
        return ApiResponse.ok(configService.printInfo());
    }

    @PutMapping("/{key}")
    @RequirePerm("sys:config:update")
    @OpLog(module = "系统管理", action = "修改参数", target = "#key")
    public ApiResponse<Void> update(@PathVariable String key,
                                    @Valid @RequestBody ConfigUpdateRequest req) {
        configService.update(key, req.configValue(), req.remark(),
                SecurityUtils.getLoginUser().username());
        return ApiResponse.ok();
    }
}
