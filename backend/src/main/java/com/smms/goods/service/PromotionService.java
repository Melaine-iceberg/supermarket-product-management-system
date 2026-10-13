package com.smms.goods.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smms.common.api.ErrorCode;
import com.smms.common.exception.BusinessException;
import com.smms.goods.dto.PromotionCreateRequest;
import com.smms.goods.entity.Goods;
import com.smms.goods.entity.Promotion;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.goods.mapper.PromotionMapper;
import com.smms.common.util.TextSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 单品限时促销服务（FR-GOODS-09）：
 * 同一商品同一时间段仅允许一条生效促销（BR-GOODS-04，应用层校验）；
 * 到期由定时任务自动失效（IR-SW-06），取价时同时校验时间区间，任务延迟不影响正确性。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PromotionService {

    private final PromotionMapper promotionMapper;
    private final GoodsMapper goodsMapper;

    /**
     * 设置促销：支持单个或批量商品；全部商品校验通过才写库，
     * 任一商品校验失败整体拒绝并指明商品。
     */
    @Transactional(rollbackFor = Exception.class)
    public void create(PromotionCreateRequest req, String operator) {
        LocalDateTime now = LocalDateTime.now();
        if (!req.endTime().isAfter(req.startTime())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "促销结束时间必须晚于开始时间");
        }
        if (!req.endTime().isAfter(now)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "促销结束时间必须晚于当前时间");
        }
        if (req.promoPrice().stripTrailingZeros().scale() > 2) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "促销价精度超限（最多2位小数）");
        }

        List<Long> goodsIds = req.goodsIds().stream().distinct().toList();
        for (Long goodsId : goodsIds) {
            Goods goods = goodsMapper.selectById(goodsId);
            if (goods == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND,
                        "商品 id=" + goodsId + " 不存在或已被删除，促销未创建");
            }
            // 促销价须低于零售价（BR-GOODS-03）
            if (req.promoPrice().compareTo(goods.getRetailPrice()) >= 0) {
                throw new BusinessException(ErrorCode.PROMO_PRICE_INVALID,
                        "商品【" + goods.getName() + "】促销价 " + req.promoPrice()
                                + " 必须低于零售价 " + goods.getRetailPrice());
            }
            // 与尚未结束的生效促销区间重叠即拒绝（BR-GOODS-04）
            Long overlap = promotionMapper.selectCount(Wrappers.<Promotion>lambdaQuery()
                    .eq(Promotion::getGoodsId, goodsId)
                    .eq(Promotion::getStatus, "ACTIVE")
                    .gt(Promotion::getEndTime, now)
                    .lt(Promotion::getStartTime, req.endTime())
                    .gt(Promotion::getEndTime, req.startTime()));
            if (overlap != null && overlap > 0) {
                throw new BusinessException(ErrorCode.PROMO_OVERLAP,
                        "商品【" + goods.getName() + "】在该时间段已存在生效促销，时间段不可重叠");
            }
        }

        for (Long goodsId : goodsIds) {
            Promotion promotion = new Promotion();
            promotion.setGoodsId(goodsId);
            promotion.setPromoPrice(req.promoPrice());
            promotion.setStartTime(req.startTime());
            promotion.setEndTime(req.endTime());
            promotion.setStatus("ACTIVE");
            promotion.setCreateBy(operator);
            promotion.setRemark(TextSanitizer.sanitize(req.remark()));
            promotionMapper.insert(promotion);
        }
    }

    /** 停用促销：仅 ACTIVE 可停用；已过期/已停用时幂等返回 */
    @Transactional(rollbackFor = Exception.class)
    public void disable(Long id) {
        Promotion promotion = promotionMapper.selectById(id);
        if (promotion == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "促销记录不存在");
        }
        if (!"ACTIVE".equals(promotion.getStatus())) {
            return;
        }
        promotion.setStatus("DISABLED");
        promotionMapper.updateById(promotion);
    }

    /** 按商品查询促销记录（FR-GOODS-09）：开始时间倒序 */
    public List<Promotion> listByGoods(Long goodsId) {
        return promotionMapper.selectList(Wrappers.<Promotion>lambdaQuery()
                .eq(Promotion::getGoodsId, goodsId)
                .orderByDesc(Promotion::getStartTime)
                .orderByDesc(Promotion::getId));
    }

    /**
     * 到期自动失效（IR-SW-06）：将已过期的 ACTIVE 促销置为 EXPIRED，返回处理条数。
     * 幂等：仅更新已到期且仍为 ACTIVE 的记录；取价侧同时校验 end_time，任务延迟不影响正确性。
     * 调度注册统一由 DynamicTaskScheduler 按 ScheduledTask 定义完成（GAP-P1-03）。
     */
    public int expireOutdated() {
        long start = System.currentTimeMillis();
        List<Promotion> outdated = promotionMapper.selectList(Wrappers.<Promotion>lambdaQuery()
                .eq(Promotion::getStatus, "ACTIVE")
                .lt(Promotion::getEndTime, LocalDateTime.now()));
        for (Promotion promotion : outdated) {
            promotion.setStatus("EXPIRED");
            promotionMapper.updateById(promotion);
        }
        // 任务执行结果与耗时写入日志（IR-SW-06；统一任务日志见 ScheduledTaskService）
        if (!outdated.isEmpty()) {
            log.info("促销到期失效: 处理 {} 条, 耗时 {}ms", outdated.size(), System.currentTimeMillis() - start);
        }
        return outdated.size();
    }
}
