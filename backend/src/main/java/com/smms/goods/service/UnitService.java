package com.smms.goods.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.goods.dto.UnitSaveRequest;
import com.smms.goods.entity.Goods;
import com.smms.goods.entity.Unit;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.goods.mapper.UnitMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 计量单位服务（FR-GOODS-08）：单位字典维护；
 * 称重商品只能选择重量单位（BR-GOODS-05）；
 * 停用后不可被新商品选择（校验在 GoodsService）。
 */
@Service
@RequiredArgsConstructor
public class UnitService {

    private final UnitMapper unitMapper;
    private final GoodsMapper goodsMapper;

    /** 全部单位（管理页展示；下拉场景由前端过滤启用状态） */
    public List<Unit> list() {
        return unitMapper.selectList(Wrappers.<Unit>lambdaQuery()
                .orderByAsc(Unit::getIsWeight)
                .orderByAsc(Unit::getId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void create(UnitSaveRequest req, String operator) {
        checkNameUnique(req.name(), null);
        Unit unit = new Unit();
        unit.setName(req.name().trim());
        unit.setSymbol(req.symbol());
        unit.setIsWeight(req.isWeight());
        unit.setStatus(req.status());
        unit.setCreateBy(operator);
        unitMapper.insert(unit);
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, UnitSaveRequest req, String operator) {
        Unit unit = requireUnit(id);
        checkNameUnique(req.name(), id);
        // 重量单位属性被商品引用时不允许变更（避免存量称重档案失真）
        if (!unit.getIsWeight().equals(req.isWeight())) {
            Long used = goodsMapper.selectCount(Wrappers.<Goods>lambdaQuery()
                    .eq(Goods::getUnitId, id));
            if (used != null && used > 0) {
                throw new BusinessException(ErrorCode.UNIT_HAS_GOODS,
                        "该单位已被 " + used + " 个商品使用，重量属性不可变更");
            }
        }
        unit.setName(req.name().trim());
        unit.setSymbol(req.symbol());
        unit.setIsWeight(req.isWeight());
        unit.setStatus(req.status());
        unit.setUpdateBy(operator);
        unitMapper.updateById(unit);
    }

    private Unit requireUnit(Long id) {
        Unit unit = unitMapper.selectById(id);
        if (unit == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "计量单位不存在或已被删除");
        }
        return unit;
    }

    private void checkNameUnique(String name, Long excludeId) {
        Long count = unitMapper.selectCount(Wrappers.<Unit>lambdaQuery()
                .eq(Unit::getName, name.trim())
                .ne(excludeId != null, Unit::getId, excludeId));
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.UNIT_NAME_EXISTS,
                    "计量单位【" + name.trim() + "】已存在");
        }
    }
}
