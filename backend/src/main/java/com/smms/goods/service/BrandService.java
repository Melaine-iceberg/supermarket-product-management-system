package com.smms.goods.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.goods.dto.BrandSaveRequest;
import com.smms.goods.entity.Brand;
import com.smms.goods.mapper.BrandMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 品牌服务（FR-GOODS-13）：品牌字典维护，供商品档案下拉选择
 * 与报表按品牌统计。
 */
@Service
@RequiredArgsConstructor
public class BrandService {

    private final BrandMapper brandMapper;

    /** 全部品牌（下拉场景由前端过滤启用状态） */
    public List<Brand> list() {
        return brandMapper.selectList(Wrappers.<Brand>lambdaQuery()
                .orderByAsc(Brand::getFirstLetter)
                .orderByAsc(Brand::getId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void create(BrandSaveRequest req, String operator) {
        checkNameUnique(req.name(), null);
        Brand brand = new Brand();
        brand.setName(req.name().trim());
        brand.setFirstLetter(normalizeFirstLetter(req));
        brand.setLogo(req.logo());
        brand.setStatus(req.status());
        brand.setCreateBy(operator);
        brandMapper.insert(brand);
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, BrandSaveRequest req, String operator) {
        Brand brand = requireBrand(id);
        checkNameUnique(req.name(), id);
        brand.setName(req.name().trim());
        brand.setFirstLetter(normalizeFirstLetter(req));
        brand.setLogo(req.logo());
        brand.setStatus(req.status());
        brand.setUpdateBy(operator);
        brandMapper.updateById(brand);
    }

    private Brand requireBrand(Long id) {
        Brand brand = brandMapper.selectById(id);
        if (brand == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "品牌不存在或已被删除");
        }
        return brand;
    }

    private void checkNameUnique(String name, Long excludeId) {
        Long count = brandMapper.selectCount(Wrappers.<Brand>lambdaQuery()
                .eq(Brand::getName, name.trim())
                .ne(excludeId != null, Brand::getId, excludeId));
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.BRAND_NAME_EXISTS,
                    "品牌【" + name.trim() + "】已存在");
        }
    }

    private String normalizeFirstLetter(BrandSaveRequest req) {
        return req.firstLetter() == null || req.firstLetter().isBlank()
                ? null : req.firstLetter().trim().toUpperCase();
    }
}
