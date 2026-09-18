package com.smms.sys.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.security.SecurityUtils;
import com.smms.sys.dto.DictItemSaveRequest;
import com.smms.sys.dto.DictVO;
import com.smms.sys.service.SysDictService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据字典接口（FR-SYS-07）。
 */
@RestController
@RequestMapping("/api/v1/dicts")
@RequiredArgsConstructor
public class SysDictController {

    private final SysDictService dictService;

    @GetMapping
    @RequirePerm("sys:dict:list")
    public ApiResponse<List<DictVO>> list() {
        return ApiResponse.ok(dictService.list());
    }

    @PostMapping("/{dictType}/items")
    @RequirePerm("sys:dict:update")
    @OpLog(module = "系统管理", action = "新增字典项", target = "#dictType + '/' + #req.value")
    public ApiResponse<Void> addItem(@PathVariable String dictType,
                                      @Valid @RequestBody DictItemSaveRequest req) {
        dictService.addItem(dictType, req, SecurityUtils.getLoginUser().username());
        return ApiResponse.ok();
    }

    @PutMapping("/items/{itemId}")
    @RequirePerm("sys:dict:update")
    @OpLog(module = "系统管理", action = "编辑字典项", target = "#req.value")
    public ApiResponse<Void> updateItem(@PathVariable Long itemId,
                                         @Valid @RequestBody DictItemSaveRequest req) {
        dictService.updateItem(itemId, req, SecurityUtils.getLoginUser().username());
        return ApiResponse.ok();
    }
}
