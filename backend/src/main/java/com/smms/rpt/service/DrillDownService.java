package com.smms.rpt.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.ErrorCode;
import com.smms.common.api.PageVO;
import com.smms.common.exception.BusinessException;
import com.smms.rpt.dto.DrillInvRecordQuery;
import com.smms.rpt.dto.DrillInvRecordRow;
import com.smms.rpt.dto.DrillLossQuery;
import com.smms.rpt.dto.DrillLossRow;
import com.smms.rpt.dto.DrillOrderQuery;
import com.smms.rpt.dto.DrillOrderRow;
import com.smms.rpt.dto.DrillPurchaseQuery;
import com.smms.rpt.dto.DrillPurchaseRow;
import com.smms.rpt.dto.DrillReceiptQuery;
import com.smms.rpt.dto.DrillReceiptRow;
import com.smms.rpt.dto.RptRoles;
import com.smms.rpt.mapper.InventoryReportMapper;
import com.smms.rpt.mapper.PurchaseReportMapper;
import com.smms.rpt.mapper.ReportMapper;
import com.smms.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 汇总数字下钻（BR-RPT-06）：任一汇总数字可下钻到构成它的明细单据
 * （点击汇总行 → 查看单据列表 → 打开单据详情，AC-16）；
 * 筛选条件与报表行同口径，逐单金额之和与汇总一致。
 * 销售单据的成本/毛利字段按角色过滤（BR-RPT-07）。
 */
@Service
@RequiredArgsConstructor
public class DrillDownService {

    private final ReportMapper reportMapper;
    private final PurchaseReportMapper purchaseReportMapper;
    private final InventoryReportMapper inventoryReportMapper;

    /** 下钻销售单：销售/商品/毛利/会员报表的汇总行 → 构成它的销售单列表 */
    public PageVO<DrillOrderRow> orders(DrillOrderQuery q, LoginUser user) {
        long total = reportMapper.countDrillOrders(q);
        if (total == 0) {
            return new PageVO<>(List.of(), 0);
        }
        Page<DrillOrderRow> p = new Page<>(q.page(), q.size(), false);
        p.setTotal(total);
        IPage<DrillOrderRow> result = reportMapper.selectDrillOrders(p, q);
        boolean canProfit = RptRoles.canViewProfit(user);
        result.setRecords(result.getRecords().stream()
                .map(r -> mask(r, canProfit)).toList());
        return PageVO.of(result);
    }

    /** 下钻采购单：采购汇总（供应商/商品）/供应商排行行 → 构成它的采购单列表 */
    public PageVO<DrillPurchaseRow> purchaseOrders(DrillPurchaseQuery q) {
        long total = purchaseReportMapper.countPurchaseDrill(q);
        if (total == 0) {
            return new PageVO<>(List.of(), 0);
        }
        Page<DrillPurchaseRow> p = new Page<>(q.page(), q.size(), false);
        p.setTotal(total);
        return PageVO.of(purchaseReportMapper.selectPurchaseDrill(p, q));
    }

    /** 下钻入库单：到货及时率供应商行 → 构成统计的入库单列表（含按期标记） */
    public PageVO<DrillReceiptRow> receipts(DrillReceiptQuery q) {
        long total = purchaseReportMapper.countReceiptDrill(q);
        if (total == 0) {
            return new PageVO<>(List.of(), 0);
        }
        Page<DrillReceiptRow> p = new Page<>(q.page(), q.size(), false);
        p.setTotal(total);
        return PageVO.of(purchaseReportMapper.selectReceiptDrill(p, q));
    }

    /** 下钻库存流水：变动明细/周转率行 → 构成汇总的流水列表（单据号可回溯来源） */
    public PageVO<DrillInvRecordRow> inventoryRecords(DrillInvRecordQuery q) {
        long total = inventoryReportMapper.countInvRecordDrill(q);
        if (total == 0) {
            return new PageVO<>(List.of(), 0);
        }
        Page<DrillInvRecordRow> p = new Page<>(q.page(), q.size(), false);
        p.setTotal(total);
        return PageVO.of(inventoryReportMapper.selectInvRecordDrill(p, q));
    }

    /** 下钻损耗单据：kind=loss 报损单 / kind=check 盘亏差异行（与损耗报表同口径） */
    public PageVO<DrillLossRow> lossDocuments(DrillLossQuery q) {
        boolean check = "check".equalsIgnoreCase(q.kind());
        if (!check && !"loss".equalsIgnoreCase(q.kind())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "损耗下钻类型不合法");
        }
        long total = check
                ? inventoryReportMapper.countCheckShortageDrill(q)
                : inventoryReportMapper.countLossDrill(q);
        if (total == 0) {
            return new PageVO<>(List.of(), 0);
        }
        Page<DrillLossRow> p = new Page<>(q.page(), q.size(), false);
        p.setTotal(total);
        return PageVO.of(check
                ? inventoryReportMapper.selectCheckShortageDrill(p, q)
                : inventoryReportMapper.selectLossDrill(p, q));
    }

    /** BR-RPT-07：无毛利权限时下钻单据的成本/毛利字段不返回（而非前端隐藏） */
    private DrillOrderRow mask(DrillOrderRow r, boolean canProfit) {
        if (canProfit) {
            return r;
        }
        return new DrillOrderRow(r.id(), r.orderNo(), r.cashierName(),
                r.counterNo(), r.memberName(), r.saleTime(), r.businessDate(), r.itemCount(),
                r.goodsAmount(), r.discountAmount(), r.payableAmount(), r.paidAmount(),
                null, null, r.returnStatus());
    }
}
