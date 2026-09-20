package com.smms.member.dto;

/**
 * 会员列表查询（FR-MEM-03）：按会员编号/姓名/手机号/等级/状态/注册日期区间筛选。
 */
public record MemberQuery(
        String memberNo,
        String name,
        String phone,
        Long levelId,
        Integer status,
        /** 注册日期起（含） */
        String beginDate,
        /** 注册日期止（含） */
        String endDate,
        int page,
        int size
) {
}
