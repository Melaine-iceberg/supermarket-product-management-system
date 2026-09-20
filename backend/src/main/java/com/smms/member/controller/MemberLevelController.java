package com.smms.member.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.member.dto.LevelSaveRequest;
import com.smms.member.entity.MemberLevel;
import com.smms.member.service.MemberLevelService;
import com.smms.security.SecurityUtils;
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
 * 会员等级规则接口（FR-MEM-05）：
 * 等级列表、新增/编辑（名称、升级门槛、折扣率、积分倍率、排序、状态）、停用/启用。
 */
@RestController
@RequestMapping("/api/v1/member-levels")
@RequiredArgsConstructor
public class MemberLevelController {

    private final MemberLevelService memberLevelService;

    /** 全部等级（按排序升序，含停用） */
    @GetMapping
    @RequirePerm("sys:mem-level:list")
    public ApiResponse<List<MemberLevel>> list() {
        return ApiResponse.ok(memberLevelService.list());
    }

    /** 新增等级（菜单未单列新增权限，与编辑同用等级规则写权限） */
    @PostMapping
    @RequirePerm("sys:mem-level:update")
    @OpLog(module = "会员管理", action = "新增会员等级", target = "#req.name")
    public ApiResponse<Void> create(@Valid @RequestBody LevelSaveRequest req) {
        memberLevelService.create(req, SecurityUtils.getLoginUser().realName());
        return ApiResponse.ok();
    }

    /** 编辑等级规则：门槛与折扣率即时生效于后续结算（BR-MEM-06 / BR-SALE-10） */
    @PutMapping("/{id}")
    @RequirePerm("sys:mem-level:update")
    @OpLog(module = "会员管理", action = "编辑会员等级", target = "#id")
    public ApiResponse<Void> update(@PathVariable Long id,
                                     @Valid @RequestBody LevelSaveRequest req) {
        memberLevelService.update(id, req, SecurityUtils.getLoginUser().realName());
        return ApiResponse.ok();
    }

    /** 停用/启用：停用后该等级折扣不再生效（收银按无折扣处理） */
    @PutMapping("/{id}/status/{status}")
    @RequirePerm("sys:mem-level:update")
    @OpLog(module = "会员管理", action = "停用启用会员等级", target = "#id")
    public ApiResponse<Void> changeStatus(@PathVariable Long id, @PathVariable Integer status) {
        memberLevelService.changeStatus(id, status);
        return ApiResponse.ok();
    }
}
