package com.smms.sale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.sale.dto.ShiftQuery;
import com.smms.sale.dto.ShiftVO;
import com.smms.sale.entity.CashierShift;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 收银班次 Mapper（DR-37，FR-SALE-12）。
 */
@Mapper
public interface CashierShiftMapper extends BaseMapper<CashierShift> {

    /** 查询收银员当前在班班次（开班/结算关联用） */
    default CashierShift selectWorking(Long cashierId) {
        return selectOne(Wrappers.<CashierShift>lambdaQuery()
                .eq(CashierShift::getCashierId, cashierId)
                .eq(CashierShift::getStatus, CashierShift.STATUS_WORKING)
                .orderByDesc(CashierShift::getId)
                .last("LIMIT 1"));
    }

    /** 班次分页（FR-SALE-12）：收银员/台号/状态/日期区间 */
    IPage<ShiftVO> selectShiftPage(Page<ShiftVO> page, @Param("q") ShiftQuery q);

    /** 班次内销售单摘要（交班单打印视图，FR-RPT-09），按销售时间升序 */
    List<ShiftVO.OrderLine> selectShiftOrders(@Param("shiftId") Long shiftId);
}
