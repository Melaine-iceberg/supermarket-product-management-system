package com.smms.common.sched.tasks;

import com.smms.common.sched.ScheduledTask;
import com.smms.goods.service.PromotionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 促销到期失效（IR-SW-06）：每 30 秒把过期 ACTIVE 促销置为 EXPIRED（幂等，可补跑） */
@Component
@RequiredArgsConstructor
public class PromotionExpireTask implements ScheduledTask {

    private final PromotionService promotionService;

    @Override
    public String code() {
        return "PROMOTION_EXPIRE";
    }

    @Override
    public String name() {
        return "促销到期失效";
    }

    @Override
    public String description() {
        return "将已过期的生效促销置为已失效；仅更新到期且仍生效的记录，重复执行/补跑不产生重复数据";
    }

    @Override
    public String cron() {
        return "*/30 * * * * ?";
    }

    @Override
    public String execute() {
        int count = promotionService.expireOutdated();
        return count == 0 ? "无到期促销" : "处理 " + count + " 条";
    }
}
