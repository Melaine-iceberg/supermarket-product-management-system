package com.smms.supplier.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smms.supplier.dto.SupplierGoodsVO;
import com.smms.supplier.entity.SupplierGoods;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SupplierGoodsMapper extends BaseMapper<SupplierGoods> {

    /** 某供应商的供货商品清单（FR-SUP-04）：含商品编码/名称/规格/分类/单位 */
    List<SupplierGoodsVO> selectGoodsBySupplier(@Param("supplierId") Long supplierId);
}
