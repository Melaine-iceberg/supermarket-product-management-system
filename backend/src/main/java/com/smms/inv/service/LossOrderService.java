package com.smms.inv.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.ErrorCode;
import com.smms.common.api.PageVO;
import com.smms.common.exception.BusinessException;
import com.smms.goods.entity.Goods;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.inv.dto.LossAuditRequest;
import com.smms.inv.dto.LossOrderCreateRequest;
import com.smms.inv.dto.LossOrderQuery;
import com.smms.inv.dto.LossOrderVO;
import com.smms.inv.dto.StockChange;
import com.smms.inv.entity.Inventory;
import com.smms.inv.entity.LossOrder;
import com.smms.inv.mapper.InventoryMapper;
import com.smms.inv.mapper.LossOrderMapper;
import com.smms.sys.entity.SysDict;
import com.smms.sys.entity.SysDictItem;
import com.smms.sys.mapper.SysDictItemMapper;
import com.smms.sys.mapper.SysDictMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 报损/报溢登记（FR-INV-10）：登记（待审核）→ 店长审核，
 * 通过后在单事务内生成 LOSS_OUT/OVERFLOW_IN 流水（NFR-REL-01）；
 * 与销售退货分开统计，已审核单据作为损耗报表（FR-RPT-04⑤）数据源。
 * 报损按当前成本单价结转并定稿金额（BR-INV-06）；报溢录入成本单价、
 * 库存按当前成本单价入账不重算均价（§8.2）。
 */
@Service
@RequiredArgsConstructor
public class LossOrderService {

    private static final long STORE_ID = 1L;
    /** 单号流水位数：BS/BY + yyyyMMdd + 4位 */
    private static final int SERIAL_WIDTH = 4;
    private static final String BIZ_LOSS = "LOSS";
    private static final String BIZ_OVERFLOW = "OVERFLOW";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_AUDITED = "AUDITED";
    private static final String STATUS_REJECTED = "REJECTED";
    /** 报损报溢原因字典（FR-SYS-07） */
    private static final String DICT_LOSS_REASON = "loss_reason";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final LossOrderMapper lossOrderMapper;
    private final GoodsMapper goodsMapper;
    private final InventoryMapper inventoryMapper;
    private final InventoryService inventoryService;
    private final SysDictMapper dictMapper;
    private final SysDictItemMapper dictItemMapper;

    // ==================== 登记（FR-INV-10） ====================

    /**
     * 登记报损/报溢单：原因须为启用中的 loss_reason 字典项（FR-SYS-07，停用项不可选）；
     * 报损成本按当前成本快照展示、审核时按实际结转成本定稿；报溢须录入成本单价。
     */
    public LossOrderVO create(LossOrderCreateRequest req, String applyName) {
        String bizType = requireBizType(req.bizType());
        Goods goods = goodsMapper.selectById(req.goodsId());
        if (goods == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "商品不存在");
        }
        BigDecimal quantity = requireQuantity(req.quantity());
        String reason = requireReason(req.reason());

        BigDecimal costPrice;
        if (BIZ_OVERFLOW.equals(bizType)) {
            costPrice = requireOverflowCost(req.costPrice());
        } else {
            Inventory inv = inventoryMapper.selectById(goods.getId());
            costPrice = inv == null || inv.getCostPrice() == null ? BigDecimal.ZERO : inv.getCostPrice();
        }

        LossOrder order = new LossOrder();
        order.setOrderNo(generateOrderNo(bizType));
        order.setStoreId(STORE_ID);
        order.setBizType(bizType);
        order.setGoodsId(goods.getId());
        order.setQuantity(quantity);
        order.setCostPrice(costPrice);
        order.setAmount(quantity.multiply(costPrice).setScale(2, RoundingMode.HALF_UP));
        order.setReason(reason);
        order.setApplyName(applyName);
        order.setStatus(STATUS_PENDING);
        order.setRemark(trimToNull(req.remark()));
        lossOrderMapper.insert(order);
        return detail(order.getId());
    }

    private String requireBizType(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (BIZ_LOSS.equals(value) || BIZ_OVERFLOW.equals(value)) {
            return value;
        }
        throw new BusinessException(ErrorCode.PARAM_INVALID, "不支持的业务类型：" + raw + "（LOSS报损/OVERFLOW报溢）");
    }

    private BigDecimal requireQuantity(BigDecimal quantity) {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "数量必须大于 0");
        }
        if (quantity.scale() > 3) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "数量最多 3 位小数");
        }
        return quantity;
    }

    /** 报溢成本单价（FR-INV-10）：必填且大于 0，最多 4 位小数（BR-INV-06 精度） */
    private BigDecimal requireOverflowCost(BigDecimal costPrice) {
        if (costPrice == null || costPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "报溢必须录入大于 0 的成本单价");
        }
        if (costPrice.scale() > 4) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "成本单价最多 4 位小数");
        }
        return costPrice.setScale(4, RoundingMode.HALF_UP);
    }

    /** 原因校验：须为启用中的 loss_reason 字典项代码（FR-SYS-07） */
    private String requireReason(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "报损报溢原因不能为空");
        }
        SysDict dict = dictMapper.selectOne(Wrappers.<SysDict>lambdaQuery()
                .eq(SysDict::getDictType, DICT_LOSS_REASON));
        if (dict == null || dict.getStatus() == null || dict.getStatus() != 1) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "报损报溢原因字典未初始化（loss_reason）");
        }
        Long count = dictItemMapper.selectCount(Wrappers.<SysDictItem>lambdaQuery()
                .eq(SysDictItem::getDictId, dict.getId())
                .eq(SysDictItem::getValue, value)
                .eq(SysDictItem::getStatus, 1));
        if (count == null || count == 0) {
            throw new BusinessException(ErrorCode.LOSS_REASON_INVALID);
        }
        return value;
    }

    /** 单号：LOSS→BS / OVERFLOW→BY + yyyyMMdd + 4位流水（取当日同前缀最大流水 +1） */
    private String generateOrderNo(String bizType) {
        String prefix = (BIZ_LOSS.equals(bizType) ? "BS" : "BY") + LocalDateTime.now().format(DATE_FMT);
        List<LossOrder> todays = lossOrderMapper.selectList(Wrappers.<LossOrder>lambdaQuery()
                .select(LossOrder::getOrderNo)
                .likeRight(LossOrder::getOrderNo, prefix));
        long max = todays.stream().map(LossOrder::getOrderNo)
                .filter(no -> no.length() == prefix.length() + SERIAL_WIDTH)
                .map(no -> no.substring(prefix.length()))
                .filter(s -> s.chars().allMatch(Character::isDigit))
                .mapToLong(Long::parseLong)
                .max().orElse(0L);
        if (max >= 9999L) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "当日单据流水号已用尽");
        }
        return prefix + String.format("%0" + SERIAL_WIDTH + "d", max + 1);
    }

    // ==================== 查询（FR-INV-10） ====================

    /** 报损/报溢单分页：单号/类型/状态/商品关键字/申请人/时间区间，申请时间倒序 */
    public PageVO<LossOrderVO> page(LossOrderQuery q) {
        int pageNo = Math.max(q.page(), 1);
        int size = q.size() <= 0 ? 20 : Math.min(q.size(), 100);
        LocalDateTime beginTime = q.beginDate() == null ? null : q.beginDate().atStartOfDay();
        LocalDateTime endTime = q.endDate() == null ? null : q.endDate().plusDays(1).atStartOfDay();
        IPage<LossOrderVO> result = lossOrderMapper.selectLossPage(new Page<>(pageNo, size), q, beginTime, endTime);
        return PageVO.of(result);
    }

    /** 单据详情（单商品） */
    public LossOrderVO detail(Long id) {
        LossOrderVO vo = lossOrderMapper.selectLossDetail(id);
        if (vo == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "报损报溢单不存在");
        }
        return vo;
    }

    // ==================== 审核（FR-INV-10） ====================

    /**
     * 店长审核：通过 → 单事务生成流水（报损 LOSS_OUT 按当前成本结转；
     * 报溢 OVERFLOW_IN 按当前成本入账不重算均价）并置为已审核；
     * 驳回 → 意见必填，单据置为已驳回（不可修改，如需重新登记请新建）。
     */
    @Transactional
    public LossOrderVO audit(Long id, LossAuditRequest req, String auditorName) {
        LossOrder order = lossOrderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "报损报溢单不存在");
        }
        if (!STATUS_PENDING.equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.LOSS_STATUS_INVALID,
                    "仅待审核的单据可审核，当前状态：" + statusLabel(order.getStatus()));
        }
        return Boolean.TRUE.equals(req.approved())
                ? approve(order, req.remark(), auditorName)
                : reject(order, req.remark(), auditorName);
    }

    /**
     * 审核通过（NFR-REL-01 单事务）：先经库存统一入口生成流水（BR-INV-02 报损出库不足即拒绝、
     * BR-INV-04 流水不可变），再条件更新状态防并发重复审核；任一步失败整体回滚。
     */
    private LossOrderVO approve(LossOrder order, String remark, String auditorName) {
        boolean loss = BIZ_LOSS.equals(order.getBizType());
        String cleanRemark = trimToNull(remark);
        String flowRemark = (loss ? "报损审核通过" : "报溢审核通过")
                + (cleanRemark == null ? "" : "：" + cleanRemark);
        BigDecimal finalCost = order.getCostPrice();
        if (loss) {
            finalCost = inventoryService.stockOut(new StockChange(order.getGoodsId(), "LOSS_OUT",
                    order.getQuantity(), null, "BS", order.getOrderNo(), auditorName, flowRemark));
        } else {
            inventoryService.stockIn(new StockChange(order.getGoodsId(), "OVERFLOW_IN",
                    order.getQuantity(), null, "BY", order.getOrderNo(), auditorName, flowRemark));
        }
        LossOrder patch = new LossOrder();
        patch.setStatus(STATUS_AUDITED);
        patch.setAuditorName(auditorName);
        patch.setAuditTime(LocalDateTime.now());
        patch.setAuditRemark(cleanRemark);
        if (loss) {
            // 报损金额按审核出库时实际结转成本定稿，与流水快照一致（BR-INV-06）
            patch.setCostPrice(finalCost);
            patch.setAmount(order.getQuantity().multiply(finalCost).setScale(2, RoundingMode.HALF_UP));
        }
        int affected = lossOrderMapper.update(patch, Wrappers.<LossOrder>lambdaUpdate()
                .eq(LossOrder::getId, order.getId())
                .eq(LossOrder::getStatus, STATUS_PENDING));
        if (affected == 0) {
            throw new BusinessException(ErrorCode.CONCURRENT_CONFLICT, "报损报溢单状态已变化，请刷新后重试");
        }
        return detail(order.getId());
    }

    /** 驳回：意见必填；单据保留记录（货损审计链完整），不可修改后重提 */
    private LossOrderVO reject(LossOrder order, String remark, String auditorName) {
        String cleanRemark = trimToNull(remark);
        if (cleanRemark == null) {
            throw new BusinessException(ErrorCode.CHECK_REASON_REQUIRED, "驳回意见不能为空");
        }
        LossOrder patch = new LossOrder();
        patch.setStatus(STATUS_REJECTED);
        patch.setAuditorName(auditorName);
        patch.setAuditTime(LocalDateTime.now());
        patch.setAuditRemark(cleanRemark);
        int affected = lossOrderMapper.update(patch, Wrappers.<LossOrder>lambdaUpdate()
                .eq(LossOrder::getId, order.getId())
                .eq(LossOrder::getStatus, STATUS_PENDING));
        if (affected == 0) {
            throw new BusinessException(ErrorCode.CONCURRENT_CONFLICT, "报损报溢单状态已变化，请刷新后重试");
        }
        return detail(order.getId());
    }

    private String statusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "PENDING" -> "待审核";
            case "AUDITED" -> "已审核";
            case "REJECTED" -> "已驳回";
            default -> status;
        };
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
