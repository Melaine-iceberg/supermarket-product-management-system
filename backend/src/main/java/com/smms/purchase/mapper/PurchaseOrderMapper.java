package com.smms.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.smms.purchase.dto.PurchaseOrderQuery;
import com.smms.purchase.dto.PurchaseOrderVO;
import com.smms.purchase.entity.PurchaseOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 采购单 Mapper（FR-PUR-03）：多维分页查询（供应商/状态/创建人/日期区间/商品筛选）。
 */
@Mapper
public interface PurchaseOrderMapper extends BaseMapper<PurchaseOrder> {

    /** 当前最大采购单号（BR-PUR-01）：按前缀行锁取号，uk_po_no 兜底防重 */
    String selectLastOrderNoForUpdate(@Param("prefix") String prefix);

    /** 采购单分页：列表展示总额、已入库数量、入库进度、结算状态；statuses 优先于 q.status（待入库选单多状态） */
    IPage<PurchaseOrderVO> selectOrderPage(IPage<PurchaseOrderVO> page,
                                           @Param("q") PurchaseOrderQuery q,
                                           @Param("beginTime") LocalDateTime beginTime,
                                           @Param("endTime") LocalDateTime endTime,
                                           @Param("statuses") java.util.List<String> statuses);

    /** 明细已入库数量原子累加（FR-PUR-06） */
    int addReceivedQty(@Param("id") Long id, @Param("qty") BigDecimal qty);

    /** 明细已退货数量原子累加（FR-PUR-08） */
    int addReturnedQty(@Param("id") Long id, @Param("qty") BigDecimal qty);

    /** 条件累加已退货数量（原子防超退：可退 = 已入库 − 已退，不足返回 0） */
    int addReturnedQtyIfEnough(@Param("id") Long id, @Param("qty") BigDecimal qty);

    /** 入库后更新采购单实收金额/状态（BR-PUR-06 乐观锁：version 不匹配返回 0） */
    int updateReceiptState(@Param("id") Long id,
                           @Param("amount") BigDecimal amount,
                           @Param("status") String status,
                           @Param("updateBy") String updateBy,
                           @Param("version") Integer version);

    /** 采购退货后冲减实收金额（BR-PUR-06 乐观锁） */
    int updateReturnState(@Param("id") Long id,
                          @Param("amount") BigDecimal amount,
                          @Param("status") String status,
                          @Param("updateBy") String updateBy,
                          @Param("version") Integer version);
}
