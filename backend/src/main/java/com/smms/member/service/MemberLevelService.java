package com.smms.member.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.member.dto.LevelSaveRequest;
import com.smms.member.entity.MemberLevel;
import com.smms.member.mapper.MemberLevelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 会员等级规则服务（FR-MEM-05）：名称、升级门槛（累计消费额）、
 * 折扣率、积分倍率、排序、状态维护；等级不提供删除（档案在用）。
 * 门槛与折扣率参与收银取价与等级自动升降（BR-MEM-06 / BR-SALE-10）。
 */
@Service
@RequiredArgsConstructor
public class MemberLevelService {

    private final MemberLevelMapper memberLevelMapper;

    /** 全部等级（按排序升序，含停用；下拉由前端过滤启用状态） */
    public List<MemberLevel> list() {
        return memberLevelMapper.selectList(Wrappers.<MemberLevel>lambdaQuery()
                .orderByAsc(MemberLevel::getSortNo)
                .orderByAsc(MemberLevel::getId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void create(LevelSaveRequest req, String operator) {
        checkNameUnique(req.getName(), null);
        MemberLevel level = new MemberLevel();
        apply(level, req, operator);
        memberLevelMapper.insert(level);
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, LevelSaveRequest req, String operator) {
        requireLevel(id);
        checkNameUnique(req.getName(), id);
        MemberLevel level = new MemberLevel();
        level.setId(id);
        apply(level, req, operator);
        memberLevelMapper.updateById(level);
    }

    /** 启用/停用：停用后该等级折扣不再生效（收银按无折扣处理） */
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, Integer status) {
        requireLevel(id);
        MemberLevel level = new MemberLevel();
        level.setId(id);
        level.setStatus(status);
        memberLevelMapper.updateById(level);
    }

    private MemberLevel requireLevel(Long id) {
        MemberLevel level = memberLevelMapper.selectById(id);
        if (level == null) {
            throw new BusinessException(ErrorCode.MEMBER_LEVEL_NOT_FOUND);
        }
        return level;
    }

    private void apply(MemberLevel level, LevelSaveRequest req, String operator) {
        level.setName(req.getName().trim());
        level.setThreshold(req.getThreshold());
        level.setDiscountRate(req.getDiscountRate());
        level.setPointRate(req.getPointRate());
        level.setSortNo(req.getSortNo());
        level.setStatus(req.getStatus());
        level.setUpdateBy(operator);
    }

    private void checkNameUnique(String name, Long excludeId) {
        Long count = memberLevelMapper.selectCount(Wrappers.<MemberLevel>lambdaQuery()
                .eq(MemberLevel::getName, name.trim())
                .ne(excludeId != null, MemberLevel::getId, excludeId));
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.MEMBER_LEVEL_NAME_EXISTS,
                    "等级名称【" + name.trim() + "】已存在");
        }
    }
}
