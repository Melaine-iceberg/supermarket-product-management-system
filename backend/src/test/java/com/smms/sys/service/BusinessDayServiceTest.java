package com.smms.sys.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 营业日计算单测（BR-RPT-02/GAP-P1-05）：参数缺省 00:00、跨零点归属前一日、
 * 营业日窗口与非法参数回退。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BusinessDayServiceTest {

    private static final String KEY = BusinessDayService.CONFIG_BUSINESS_DAY_START;

    @Mock
    private SysConfigService configService;

    private BusinessDayService service;

    @BeforeEach
    void setUp() {
        service = new BusinessDayService(configService);
    }

    @Test
    @DisplayName("默认 00:00：任意自然日时刻归属当日")
    void defaultStartAttributesSameCalendarDay() {
        when(configService.getStr(KEY, "00:00")).thenReturn("00:00");

        assertThat(service.businessDate(LocalDateTime.of(2026, 3, 10, 0, 0))).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(service.businessDate(LocalDateTime.of(2026, 3, 10, 23, 59))).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(service.dayStart()).isEqualTo(LocalTime.MIDNIGHT);
    }

    @Test
    @DisplayName("起始时刻 06:00：05:59 归前一日、06:00 归当日（跨零点单据）")
    void configuredStartShiftsEarlyMorningToPreviousDay() {
        when(configService.getStr(KEY, "00:00")).thenReturn("06:00");

        assertThat(service.businessDate(LocalDateTime.of(2026, 3, 10, 5, 59)))
                .isEqualTo(LocalDate.of(2026, 3, 9));
        assertThat(service.businessDate(LocalDateTime.of(2026, 3, 10, 6, 0)))
                .isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(service.businessDate(LocalDateTime.of(2026, 3, 10, 23, 30)))
                .isEqualTo(LocalDate.of(2026, 3, 10));
    }

    @Test
    @DisplayName("营业日窗口 = [起始时刻, 次日起始时刻)，用于按业务时刻过滤的报表")
    void businessDayWindowUsesConfiguredStart() {
        when(configService.getStr(KEY, "00:00")).thenReturn("06:00");

        assertThat(service.dayBegin(LocalDate.of(2026, 3, 10)))
                .isEqualTo(LocalDateTime.of(2026, 3, 10, 6, 0));
        assertThat(service.dayEnd(LocalDate.of(2026, 3, 10)))
                .isEqualTo(LocalDateTime.of(2026, 3, 11, 6, 0));
    }

    @Test
    @DisplayName("参数缺失或非法：回退 00:00 且不抛异常")
    void invalidConfigFallsBackToMidnight() {
        when(configService.getStr(KEY, "00:00")).thenReturn(null);
        assertThat(service.dayStart()).isEqualTo(LocalTime.MIDNIGHT);

        when(configService.getStr(KEY, "00:00")).thenReturn("not-a-time");
        assertThat(service.dayStart()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(service.businessDate(LocalDateTime.of(2026, 3, 10, 1, 0))).isEqualTo(LocalDate.of(2026, 3, 10));
    }
}
