package com.smms.supplier.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smms.supplier.dto.SupplierStatVO;
import com.smms.supplier.entity.Supplier;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SupplierMapper extends BaseMapper<Supplier> {

    /** 当前最大供应商编码（BR-SUP-03）：行锁串行化取号 */
    String selectLastSupplierCodeForUpdate();

    /** 供应商业务统计（FR-SUP-02）：在途单数/未结算金额/最近合作时间/供货商品数，一次聚合避免 N+1 */
    List<SupplierStatVO> selectStatsBySupplierIds(@Param("supplierIds") List<Long> supplierIds);
}
