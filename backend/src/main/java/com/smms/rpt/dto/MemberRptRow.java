package com.smms.rpt.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 会员报表通用行：增长趋势 / 消费排行 / 等级分布 / 沉睡清单 / 生日提醒复用同一结构。
 * 手机号在服务层按角色脱敏（BR-MEM-08：完整手机号仅店长及以上可见）。
 */
public record MemberRptRow(
        Long id,
        String memberNo,
        String name,
        String phone,
        String levelName,
        Integer points,
        BigDecimal totalConsume,
        Integer consumeTimes,
        LocalDateTime lastConsumeTime,
        LocalDateTime registerTime,
        LocalDate birthday,
        /** 增长趋势：日期 */
        LocalDate bizDate,
        /** 增长趋势：当日新增 */
        Long newCount,
        /** 增长趋势：累计会员数 */
        Long cumulativeCount,
        /** 等级分布：该等级会员数 */
        Long memberCount,
        /** 沉睡天数（服务层计算） */
        Integer sleepingDays) {

    public MemberRptRow {
        phone = phone == null ? "" : phone;
    }
}
