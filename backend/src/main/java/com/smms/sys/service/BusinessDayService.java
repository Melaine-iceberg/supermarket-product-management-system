package com.smms.sys.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 营业日计算统一入口（BR-RPT-02）：单据按业务时刻归属营业日，
 * 业务时刻早于系统参数 business_day_start（默认 00:00）则归属前一自然日；
 * 营业日在单据落库/定稿时写入字段，报表按该字段聚合，不在运行时计算。
 *
 * <p>此前该逻辑分散在销售结算/销售退货/看板三处（GAP-P1-05），统一后
 * 采购、入库、采购退货、盘点、报损、库存流水同样可复用。
 */
@Service
@RequiredArgsConstructor
public class BusinessDayService {

    /** 营业日起始时刻参数键（db/init-data.sql，TIME 类型） */
    public static final String CONFIG_BUSINESS_DAY_START = "business_day_start";

    private final SysConfigService configService;

    /** 营业日起始时刻，参数缺失/非法时回退 00:00 */
    public LocalTime dayStart() {
        try {
            return LocalTime.parse(configService.getStr(CONFIG_BUSINESS_DAY_START, "00:00"));
        } catch (Exception e) {
            return LocalTime.MIDNIGHT;
        }
    }

    /** 业务时刻所属营业日（BR-RPT-02）：早于起始时刻归前一日 */
    public LocalDate businessDate(LocalDateTime moment) {
        LocalTime start = dayStart();
        LocalDateTime time = moment == null ? LocalDateTime.now() : moment;
        return time.toLocalTime().isBefore(start) ? time.toLocalDate().minusDays(1) : time.toLocalDate();
    }

    /** 当前营业日（看板"今日"、默认报表区间等场景） */
    public LocalDate today() {
        return businessDate(LocalDateTime.now());
    }

    /** 营业日窗口起（[dayStart, nextDayStart) 左闭右开，用于按业务时刻过滤的报表） */
    public LocalDateTime dayBegin(LocalDate businessDate) {
        return LocalDateTime.of(businessDate, dayStart());
    }

    /** 营业日窗口止（次日营业日起始时刻） */
    public LocalDateTime dayEnd(LocalDate businessDate) {
        return LocalDateTime.of(businessDate.plusDays(1), dayStart());
    }
}
