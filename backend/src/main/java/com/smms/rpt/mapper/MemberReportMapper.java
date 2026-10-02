package com.smms.rpt.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.rpt.dto.MemberRptRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会员报表聚合查询（FR-RPT-07）：增长趋势、消费排行、等级分布、
 * 沉睡会员清单、生日提醒。SQL 见 mapper/rpt/MemberReportMapper.xml。
 */
@Mapper
public interface MemberReportMapper {

    /** 每日新增会员数（增长趋势） */
    List<MemberRptRow> selectDailyNewMembers(@Param("beginTime") LocalDateTime beginTime,
                                             @Param("endTime") LocalDateTime endTime);

    /** 区间开始前累计会员数（趋势基线） */
    long countMembersBefore(@Param("before") LocalDateTime before);

    /** 会员消费排行 TOP N（按累计消费额） */
    List<MemberRptRow> selectConsumptionRanking(@Param("topN") int topN);

    /** 会员等级分布 */
    List<MemberRptRow> selectLevelDistribution();

    /** 沉睡会员总数（N 天未消费） */
    long countSleeping(@Param("deadline") LocalDateTime deadline);

    /** 沉睡会员清单（N 天未消费） */
    IPage<MemberRptRow> selectSleeping(Page<MemberRptRow> page, @Param("deadline") LocalDateTime deadline);

    /** 生日提醒清单（scope=month 当月 / week 本周 7 天内） */
    List<MemberRptRow> selectBirthday(@Param("scope") String scope);
}
