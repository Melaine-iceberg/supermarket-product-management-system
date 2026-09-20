package com.smms.member.controller;

import com.smms.common.annotation.OpLog;
import com.smms.common.annotation.RequirePerm;
import com.smms.common.api.ApiResponse;
import com.smms.common.api.PageVO;
import com.smms.member.dto.MemberCreateRequest;
import com.smms.member.dto.MemberListVO;
import com.smms.member.dto.MemberQuery;
import com.smms.member.dto.MemberStatusRequest;
import com.smms.member.dto.MemberUpdateRequest;
import com.smms.member.service.MemberService;
import com.smms.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会员档案接口（FR-MEM-01~03）：
 * 注册、信息维护、停用/启用、多维分页查询（含等级名称与业务统计）。
 */
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    /** 列表多维检索（FR-MEM-03）：编号/姓名/手机号模糊，等级/状态精确，注册日期区间 */
    @GetMapping
    @RequirePerm("sys:mem:list")
    public ApiResponse<PageVO<MemberListVO>> page(
            @RequestParam(required = false) String memberNo,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) Long levelId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String beginDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        MemberQuery query = new MemberQuery(memberNo, name, phone, levelId, status,
                beginDate, endDate, page, size);
        return ApiResponse.ok(memberService.page(query));
    }

    /** 会员详情（FR-MEM-03）：档案 + 等级 + 积分/储值/累计消费统计 */
    @GetMapping("/{id}")
    @RequirePerm("sys:mem:list")
    public ApiResponse<MemberListVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(memberService.detail(id));
    }

    /** 注册会员（FR-MEM-01）：姓名+手机号必填，编号自动生成，初始普通会员 */
    @PostMapping
    @RequirePerm("sys:mem:create")
    @OpLog(module = "会员管理", action = "注册会员", target = "#req.phone")
    public ApiResponse<MemberListVO> create(@Valid @RequestBody MemberCreateRequest req) {
        return ApiResponse.ok(memberService.create(req, SecurityUtils.getLoginUser().realName()));
    }

    /** 编辑会员档案（FR-MEM-02）：手机号修改须唯一；积分/储值/累计消费不可手工改 */
    @PutMapping("/{id}")
    @RequirePerm("sys:mem:update")
    @OpLog(module = "会员管理", action = "编辑会员", target = "#id")
    public ApiResponse<Void> update(@PathVariable Long id,
                                     @Valid @RequestBody MemberUpdateRequest req) {
        memberService.update(id, req);
        return ApiResponse.ok();
    }

    /** 停用/启用会员（FR-MEM-02，BR-MEM-03）：停用后不享受会员价与折扣 */
    @PutMapping("/{id}/status")
    @RequirePerm("sys:mem:status")
    @OpLog(module = "会员管理", action = "停用启用会员", target = "#id")
    public ApiResponse<Void> updateStatus(@PathVariable Long id,
                                           @Valid @RequestBody MemberStatusRequest req) {
        memberService.changeStatus(id, req.getStatus());
        return ApiResponse.ok();
    }
}
