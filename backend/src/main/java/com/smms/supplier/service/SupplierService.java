package com.smms.supplier.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smms.common.api.ErrorCode;
import com.smms.common.api.PageVO;
import com.smms.common.exception.BusinessException;
import com.smms.common.util.MaskUtils;
import com.smms.goods.entity.Goods;
import com.smms.goods.mapper.GoodsMapper;
import com.smms.security.SecurityUtils;
import com.smms.supplier.dto.SupplierGoodsSaveRequest;
import com.smms.supplier.dto.SupplierGoodsVO;
import com.smms.supplier.dto.SupplierListVO;
import com.smms.supplier.dto.SupplierQuery;
import com.smms.supplier.dto.SupplierSaveRequest;
import com.smms.supplier.dto.SupplierStatVO;
import com.smms.supplier.entity.Supplier;
import com.smms.supplier.entity.SupplierGoods;
import com.smms.supplier.mapper.SupplierGoodsMapper;
import com.smms.supplier.mapper.SupplierMapper;
import com.smms.sys.service.SysDictService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 供应商档案服务（FR-SUP-01~04）：
 * 新增（编码 S+5位流水自动生成、名称唯一）、编辑（银行账号/纳税人识别号
 * 脱敏提交时保留原值）、停用/启用、多维分页查询（含在途/未结算/最近合作统计）、
 * 供货商品维护（全量覆盖、默认供应商唯一）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierService {

    /** 大陆手机号（BR-SUP-01） */
    private static final Pattern MOBILE = Pattern.compile("^1[3-9]\\d{9}$");

    /** 固话：0区号-7/8位，连字符可省（BR-SUP-01） */
    private static final Pattern LANDLINE = Pattern.compile("^0\\d{2,3}-?\\d{7,8}$");

    /** 供应商编码流水上限：S+5位（BR-SUP-03） */
    private static final int MAX_SEQ = 99_999;

    private static final int RETRY = 3;

    /** 采购提前期缺省值（天） */
    private static final int DEFAULT_LEAD_TIME = 3;

    /** 结算方式字典（FR-SYS-07/GAP-P1-09）：停用项不可被新档案选择，字典缺失时回退内置枚举 */
    private static final String DICT_SETTLE_TYPE = "settle_type";
    private static final Set<String> SETTLE_TYPES = Set.of("CASH", "MONTHLY", "CREDIT");

    private final SupplierMapper supplierMapper;
    private final SupplierGoodsMapper supplierGoodsMapper;
    private final GoodsMapper goodsMapper;
    private final SysDictService dictService;

    // ==================== 新增（FR-SUP-01） ====================

    /**
     * 新增供应商：名称唯一、电话格式校验；编码 S+5位流水自动生成（BR-SUP-03）；
     * 结算方式为账期时账期天数必填；初始累计采购额为 0。
     */
    @Transactional(rollbackFor = Exception.class)
    public SupplierListVO create(SupplierSaveRequest req, String operatorRealName) {
        String name = req.getName().trim();
        checkNameUnique(name, null);
        String phone = trimToNull(req.getPhone());
        checkPhoneFormat(phone);
        String settleType = normalizeSettleType(req.getSettleType());
        Integer creditDays = resolveCreditDays(settleType, req.getCreditDays());
        Integer leadTimeDays = req.getLeadTimeDays() == null ? DEFAULT_LEAD_TIME : req.getLeadTimeDays();

        Supplier supplier = new Supplier();
        supplier.setSupplierCode(nextSupplierCode());
        supplier.setName(name);
        supplier.setContactPerson(trimToNull(req.getContactPerson()));
        supplier.setPhone(phone);
        supplier.setAddress(trimToNull(req.getAddress()));
        supplier.setEmail(trimToNull(req.getEmail()));
        supplier.setBankName(trimToNull(req.getBankName()));
        supplier.setBankAccount(trimToNull(req.getBankAccount()));
        supplier.setTaxNo(trimToNull(req.getTaxNo()));
        supplier.setSettleType(settleType);
        supplier.setCreditDays(creditDays);
        supplier.setLeadTimeDays(leadTimeDays);
        supplier.setTotalPurchaseAmount(BigDecimal.ZERO);
        supplier.setStatus(1);
        supplier.setRemark(trimToNull(req.getRemark()));
        supplier.setCreateBy(operatorRealName);
        insertWithRetry(supplier);
        return toVO(supplier, null);
    }

    // ==================== 编辑（FR-SUP-01） ====================

    /**
     * 编辑供应商档案：编码不可修改（不受理）；名称唯一；
     * 银行账号/纳税人识别号为脱敏值或空时保留原值（BR-SUP-01，无权限角色回显脱敏）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, SupplierSaveRequest req, String operatorRealName) {
        Supplier old = requireSupplier(id);
        String name = req.getName().trim();
        checkNameUnique(name, id);
        String phone = trimToNull(req.getPhone());
        checkPhoneFormat(phone);
        String settleType = normalizeSettleType(req.getSettleType());
        Integer creditDays = resolveCreditDays(settleType, req.getCreditDays());
        Integer leadTimeDays = req.getLeadTimeDays() == null ? old.getLeadTimeDays() : req.getLeadTimeDays();

        supplierMapper.update(null, Wrappers.<Supplier>lambdaUpdate()
                .eq(Supplier::getId, id)
                .set(Supplier::getName, name)
                .set(Supplier::getContactPerson, trimToNull(req.getContactPerson()))
                .set(Supplier::getPhone, phone)
                .set(Supplier::getAddress, trimToNull(req.getAddress()))
                .set(Supplier::getEmail, trimToNull(req.getEmail()))
                .set(Supplier::getBankName, trimToNull(req.getBankName()))
                .set(Supplier::getBankAccount, keepIfMasked(trimToNull(req.getBankAccount()), old.getBankAccount()))
                .set(Supplier::getTaxNo, keepIfMasked(trimToNull(req.getTaxNo()), old.getTaxNo()))
                .set(Supplier::getSettleType, settleType)
                .set(Supplier::getCreditDays, creditDays)
                .set(Supplier::getLeadTimeDays, leadTimeDays)
                .set(Supplier::getRemark, trimToNull(req.getRemark()))
                .set(Supplier::getUpdateBy, operatorRealName));
    }

    // ==================== 停用/启用（FR-SUP-03） ====================

    /** 停用后不可被新采购单选择；历史单据与结算记录保留可查（档案保留） */
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, Integer status) {
        Supplier supplier = requireSupplier(id);
        supplier.setStatus(status);
        supplierMapper.updateById(supplier);
    }

    // ==================== 查询（FR-SUP-02） ====================

    /** 分页多维检索：名称/编码/联系人/电话模糊，状态精确，供货品类 EXISTS 筛选 */
    public PageVO<SupplierListVO> page(SupplierQuery q) {
        int page = Math.max(q.page(), 1);
        int size = q.size() <= 0 ? 20 : Math.min(q.size(), 100);
        String name = trimToNull(q.name());
        String code = trimToNull(q.code());
        String contactPerson = trimToNull(q.contactPerson());
        String phone = trimToNull(q.phone());
        LambdaQueryWrapper<Supplier> wrapper = Wrappers.<Supplier>lambdaQuery()
                .like(name != null, Supplier::getName, name)
                .like(code != null, Supplier::getSupplierCode, code)
                .like(contactPerson != null, Supplier::getContactPerson, contactPerson)
                .like(phone != null, Supplier::getPhone, phone)
                .eq(q.status() != null, Supplier::getStatus, q.status())
                // 供货品类筛选（FR-SUP-02）：该分类下存在供货商品的供应商
                .apply(q.categoryId() != null,
                        "EXISTS (SELECT 1 FROM supplier_goods sg JOIN goods g ON g.id = sg.goods_id "
                                + "WHERE sg.supplier_id = supplier.id AND g.category_id = {0})",
                        q.categoryId())
                .orderByDesc(Supplier::getCreateTime)
                .orderByDesc(Supplier::getId);
        Page<Supplier> result = supplierMapper.selectPage(new Page<>(page, size), wrapper);
        return new PageVO<>(mapWithStats(result.getRecords()), result.getTotal());
    }

    /** 供应商详情（含在途/未结算/最近合作等业务统计） */
    public SupplierListVO detail(Long id) {
        Supplier supplier = requireSupplier(id);
        return mapWithStats(List.of(supplier)).get(0);
    }

    // ==================== 供货商品（FR-SUP-04） ====================

    /** 某供应商的供货商品清单：默认供应商优先 */
    public List<SupplierGoodsVO> listGoods(Long supplierId) {
        requireSupplier(supplierId);
        return supplierGoodsMapper.selectGoodsBySupplier(supplierId);
    }

    /**
     * 维护供货商品（FR-SUP-04）：全量覆盖保存；
     * 最近供货时间（入库回写）在覆盖时保留；商品设为默认供应商时，
     * 自动清除该商品在其他供应商上的默认标记（一商品至多一个默认）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveGoods(Long supplierId, SupplierGoodsSaveRequest req, String operatorRealName) {
        requireSupplier(supplierId);
        List<SupplierGoodsSaveRequest.Item> items =
                req.getItems() == null ? List.of() : req.getItems();

        // 商品有效性：存在且清单内不重复
        Set<Long> goodsIds = items.stream()
                .map(SupplierGoodsSaveRequest.Item::getGoodsId)
                .collect(Collectors.toSet());
        if (goodsIds.size() != items.size()) {
            throw new BusinessException(ErrorCode.SUPPLIER_GOODS_DUPLICATED);
        }
        if (!goodsIds.isEmpty()
                && goodsMapper.selectCount(Wrappers.<Goods>lambdaQuery().in(Goods::getId, goodsIds))
                        != goodsIds.size()) {
            throw new BusinessException(ErrorCode.SUPPLIER_GOODS_INVALID);
        }

        // 保留最近供货时间（业务派生，人工维护不可见也不可清）
        Map<Long, java.time.LocalDateTime> lastSupplyMap = new HashMap<>();
        for (SupplierGoods sg : supplierGoodsMapper.selectList(Wrappers.<SupplierGoods>lambdaQuery()
                .eq(SupplierGoods::getSupplierId, supplierId))) {
            lastSupplyMap.put(sg.getGoodsId(), sg.getLastSupplyTime());
        }

        // 全量覆盖：先删后插
        supplierGoodsMapper.delete(Wrappers.<SupplierGoods>lambdaQuery()
                .eq(SupplierGoods::getSupplierId, supplierId));
        for (SupplierGoodsSaveRequest.Item item : items) {
            SupplierGoods sg = new SupplierGoods();
            sg.setSupplierId(supplierId);
            sg.setGoodsId(item.getGoodsId());
            sg.setSupplyPrice(item.getSupplyPrice());
            sg.setIsDefault(isDefault(item) ? 1 : 0);
            sg.setLastSupplyTime(lastSupplyMap.get(item.getGoodsId()));
            sg.setCreateBy(operatorRealName);
            supplierGoodsMapper.insert(sg);
        }

        // 默认供应商唯一（FR-SUP-04）：清除该商品在其他供应商的默认标记
        Set<Long> defaultGoodsIds = items.stream()
                .filter(this::isDefault)
                .map(SupplierGoodsSaveRequest.Item::getGoodsId)
                .collect(Collectors.toSet());
        for (Long goodsId : defaultGoodsIds) {
            supplierGoodsMapper.update(null, Wrappers.<SupplierGoods>lambdaUpdate()
                    .eq(SupplierGoods::getGoodsId, goodsId)
                    .ne(SupplierGoods::getSupplierId, supplierId)
                    .eq(SupplierGoods::getIsDefault, 1)
                    .set(SupplierGoods::getIsDefault, 0));
        }
    }

    // ==================== 内部方法 ====================

    private Supplier requireSupplier(Long id) {
        Supplier supplier = supplierMapper.selectById(id);
        if (supplier == null) {
            throw new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND);
        }
        return supplier;
    }

    /** 名称全局唯一校验（含停用供应商；BR-SUP-01） */
    private void checkNameUnique(String name, Long excludeId) {
        Long count = supplierMapper.selectCount(Wrappers.<Supplier>lambdaQuery()
                .eq(Supplier::getName, name)
                .ne(excludeId != null, Supplier::getId, excludeId));
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.SUPPLIER_NAME_EXISTS);
        }
    }

    /** 电话格式校验：大陆手机号或固话，允许为空（BR-SUP-01） */
    private void checkPhoneFormat(String phone) {
        if (phone == null) {
            return;
        }
        if (!MOBILE.matcher(phone).matches() && !LANDLINE.matcher(phone).matches()) {
            throw new BusinessException(ErrorCode.SUPPLIER_PHONE_INVALID);
        }
    }

    /** 结算方式归一化：空值默认现结 */
    private String normalizeSettleType(String settleType) {
        String value = settleType == null || settleType.isBlank() ? "CASH" : settleType.trim();
        // 取值以数据字典为准（FR-SYS-07/GAP-P1-09：停用项不可选）；字典缺失/为空时按内置枚举兜底
        Set<String> enabled = dictService.enabledValues(DICT_SETTLE_TYPE);
        Set<String> allowed = enabled.isEmpty() ? SETTLE_TYPES : enabled;
        if (!allowed.contains(value)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "结算方式不合法或已被停用（数据字典 settle_type）：" + value);
        }
        return value;
    }

    /** 账期天数：结算方式为账期时必填，其他方式清空 */
    private Integer resolveCreditDays(String settleType, Integer creditDays) {
        if (!"CREDIT".equals(settleType)) {
            return null;
        }
        if (creditDays == null) {
            throw new BusinessException(ErrorCode.SUPPLIER_CREDIT_DAYS_REQUIRED);
        }
        return creditDays;
    }

    /** 脱敏值/空值原样提交时保留库中原值（BR-SUP-01，无敏感字段权限的角色） */
    private String keepIfMasked(String submitted, String oldValue) {
        if (submitted == null || submitted.startsWith("****")) {
            return oldValue;
        }
        return submitted;
    }

    /** 生成下一供应商编码（BR-SUP-03）：行锁取最大号，uk 兜底防重 */
    private String nextSupplierCode() {
        String last = supplierMapper.selectLastSupplierCodeForUpdate();
        int seq = last == null ? 1 : Integer.parseInt(last.substring(1)) + 1;
        if (seq > MAX_SEQ) {
            throw new BusinessException(ErrorCode.SUPPLIER_CODE_EXCEED);
        }
        return "S%05d".formatted(seq);
    }

    /** 插入并对编码唯一键冲突重试（并发取号兜底） */
    private void insertWithRetry(Supplier supplier) {
        for (int i = 0; i < RETRY; i++) {
            try {
                supplierMapper.insert(supplier);
                return;
            } catch (DuplicateKeyException e) {
                if (i == RETRY - 1) {
                    throw e;
                }
                log.warn("供应商编码 {} 冲突，自动重试取号", supplier.getSupplierCode());
                supplier.setSupplierCode(nextSupplierCode());
            }
        }
    }

    /** 批量回填业务统计（一次聚合查询，避免逐行 N+1） */
    private List<SupplierListVO> mapWithStats(List<Supplier> suppliers) {
        if (suppliers.isEmpty()) {
            return List.of();
        }
        List<Long> ids = suppliers.stream().map(Supplier::getId).toList();
        Map<Long, SupplierStatVO> statMap = supplierMapper.selectStatsBySupplierIds(ids).stream()
                .collect(Collectors.toMap(SupplierStatVO::getSupplierId, Function.identity()));
        return suppliers.stream()
                .map(s -> toVO(s, statMap.get(s.getId())))
                .toList();
    }

    private boolean isDefault(SupplierGoodsSaveRequest.Item item) {
        return item.getIsDefault() != null && item.getIsDefault() == 1;
    }

    private SupplierListVO toVO(Supplier s, SupplierStatVO stat) {
        SupplierListVO vo = new SupplierListVO();
        vo.setId(s.getId());
        vo.setSupplierCode(s.getSupplierCode());
        vo.setName(s.getName());
        vo.setContactPerson(s.getContactPerson());
        vo.setPhone(s.getPhone());
        vo.setAddress(s.getAddress());
        vo.setEmail(s.getEmail());
        vo.setBankName(s.getBankName());
        // 银行账号与纳税人识别号仅店长及以上可见（BR-SUP-01）：
        // 无权限角色账号脱敏展示（未录入则不返回）、识别号一律不返回
        if (SecurityUtils.canViewSupplierSensitive()) {
            vo.setBankAccount(s.getBankAccount());
            vo.setTaxNo(s.getTaxNo());
        } else {
            vo.setBankAccount(s.getBankAccount() == null ? null : MaskUtils.maskBankAccount(s.getBankAccount()));
        }
        vo.setSettleType(s.getSettleType());
        vo.setCreditDays(s.getCreditDays());
        vo.setLeadTimeDays(s.getLeadTimeDays());
        vo.setTotalPurchaseAmount(s.getTotalPurchaseAmount());
        vo.setStatus(s.getStatus());
        vo.setRemark(s.getRemark());
        vo.setCreateBy(s.getCreateBy());
        vo.setCreateTime(s.getCreateTime());
        vo.setUpdateBy(s.getUpdateBy());
        vo.setUpdateTime(s.getUpdateTime());
        if (stat != null) {
            vo.setInTransitCount(stat.getInTransitCount() == null ? 0L : stat.getInTransitCount());
            vo.setUnsettledAmount(stat.getUnsettledAmount() == null ? BigDecimal.ZERO : stat.getUnsettledAmount());
            vo.setLastCoopTime(stat.getLastCoopTime());
            vo.setGoodsCount(stat.getGoodsCount() == null ? 0L : stat.getGoodsCount());
        } else {
            vo.setInTransitCount(0L);
            vo.setUnsettledAmount(BigDecimal.ZERO);
            vo.setGoodsCount(0L);
        }
        return vo;
    }

    private String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
