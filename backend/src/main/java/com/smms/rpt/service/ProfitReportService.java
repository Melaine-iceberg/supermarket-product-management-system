package com.smms.rpt.service;

import com.smms.common.api.ErrorCode;
import com.smms.common.api.PageVO;
import com.smms.common.exception.BusinessException;
import com.smms.rpt.dto.GoodsRankRow;
import com.smms.rpt.dto.NegativeProfitRow;
import com.smms.rpt.dto.ReportRange;
import com.smms.rpt.dto.RptRoles;
import com.smms.rpt.dto.SalesReportVO;
import com.smms.rpt.mapper.ReportMapper;
import com.smms.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 毛利分析（FR-RPT-05）：按商品/分类/时段分析毛利额与毛利率；
 * 毛利率 = (净销售 − 销售成本) / 净销售 × 100%（BR-RPT-01）；
 * 负毛利商品清单用于发现定价错误与异常改价。
 * 全部为毛利类指标：仅店长及以上角色可见（BR-RPT-07，菜单已限制，服务层再校验）。
 */
@Service
@RequiredArgsConstructor
public class ProfitReportService {

    private final GoodsReportService goodsReportService;
    private final SalesReportService salesReportService;
    private final ReportMapper reportMapper;

    /** 按商品分析毛利（默认按毛利额倒序，含毛利率） */
    public PageVO<GoodsRankRow> byGoods(ReportRange range, Long categoryId,
                                        int page, int size, LoginUser user) {
        requireProfitRole(user);
        return goodsReportService.ranking(range, "profitAmount", categoryId, page, size, user);
    }

    /** 按商品分析毛利导出（FR-RPT-08）：不分页，取前 limit 行，超限由调用方校验 */
    public List<GoodsRankRow> byGoodsForExport(ReportRange range, Long categoryId,
                                               int limit, LoginUser user) {
        requireProfitRole(user);
        return goodsReportService.rankingForExport(range, "profitAmount", categoryId, limit, user);
    }

    /** 按分类分析毛利（含毛利率与占比） */
    public List<GoodsRankRow> byCategory(ReportRange range, LoginUser user) {
        requireProfitRole(user);
        return goodsReportService.categoryStructure(range, user);
    }

    /** 按时段（营业日）分析毛利：与销售日报同口径 */
    public SalesReportVO byDay(ReportRange range, LoginUser user) {
        requireProfitRole(user);
        return salesReportService.daily(range, false, user);
    }

    /** 负毛利商品清单：成交价低于成本价（FR-RPT-05） */
    public List<NegativeProfitRow> negative(ReportRange range, LoginUser user) {
        requireProfitRole(user);
        return reportMapper.selectNegativeProfit(range.beginDate(), range.endDate(), 1000);
    }

    /** 毛利类指标仅店长及以上可见（BR-RPT-07） */
    private void requireProfitRole(LoginUser user) {
        if (!RptRoles.canViewProfit(user)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "毛利类指标仅店长及以上角色可见（BR-RPT-07）");
        }
    }
}
