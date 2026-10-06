package com.smms.common.api;

import lombok.Getter;

/**
 * 统一错误码体系（NFR-REL-06：可预期异常返回明确业务错误码与中文提示）。
 * 约定：0 成功；4xx 通用客户端错误；分段号位保留给各业务模块扩展。
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, "成功"),

    // ---------- 通用客户端错误 ----------
    PARAM_INVALID(400, "请求参数不合法"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "无权限执行该操作"),
    NOT_FOUND(404, "请求的资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),
    TOO_MANY_REQUESTS(429, "请求过于频繁，请稍后重试"),

    // ---------- 通用服务端错误 ----------
    SYSTEM_ERROR(500, "系统繁忙，请稍后重试"),
    SERVICE_UNAVAILABLE(503, "服务暂不可用，请稍后重试"),

    // ---------- 通用业务错误（各模块错误码后续按段扩展） ----------
    BUSINESS_ERROR(1000, "业务处理失败"),
    CONCURRENT_CONFLICT(1001, "操作冲突，请刷新后重试"),

    // ---------- SYS 系统模块（1100 ~ 1199，FR-SYS-01 ~ 09） ----------
    USERNAME_PASSWORD_ERROR(1101, "用户名或密码错误"),
    ACCOUNT_LOCKED(1102, "账号已锁定，请稍后重试"),
    ACCOUNT_DISABLED(1103, "账号已停用，请联系管理员"),
    USERNAME_EXISTS(1104, "用户名已存在"),
    ROLE_CODE_EXISTS(1105, "角色代码已存在"),
    WEAK_PASSWORD(1106, "密码强度不足"),
    OLD_PASSWORD_ERROR(1107, "原密码不正确"),
    BUILTIN_PROTECTED(1108, "内置对象受保护，不允许该操作"),
    SUPER_ADMIN_ROLE_PROTECTED(1109, "超级管理员角色仅可授予初始账号（BR-SYS-03）"),
    SENSITIVE_PWD_REQUIRED(1110, "该操作为敏感操作，需输入当前账号密码二次确认（BR-SYS-06）"),
    SENSITIVE_PWD_ERROR(1111, "密码错误，操作未执行"),
    AUTHORIZER_AUTH_FAILED(1112, "授权账号或密码错误"),
    AUTHORIZER_NOT_MANAGER(1113, "授权人须为店长及以上账号（FR-SALE-07/11、FR-PUR-06）"),

    // ---------- CAT 分类模块（1200 ~ 1299，FR-CAT-01 ~ 07） ----------
    CAT_NAME_EXISTS(1201, "同级下已存在同名分类"),
    CAT_LEVEL_EXCEED(1202, "分类层级最多3级"),
    CAT_PARENT_INVALID(1203, "上级分类不能是自身或其子孙分类"),
    CAT_HAS_CHILDREN(1204, "存在子分类，禁止删除"),
    CAT_HAS_GOODS(1205, "分类下存在商品，禁止删除，请改用停用"),
    CAT_PARENT_DISABLED(1206, "上级分类已停用，请先启用上级分类"),
    CAT_ANCESTOR_DISABLED(1207, "上级分类已停用，无法启用该分类，请先启用上级分类"),

    // ---------- GOODS 商品模块（1300 ~ 1399，FR-GOODS-01 ~ 13） ----------
    GOODS_CODE_EXISTS(1301, "商品编码已存在"),
    GOODS_BARCODE_EXISTS(1302, "商品条码已存在"),
    GOODS_HAS_BUSINESS(1303, "商品已发生业务，禁止删除，请改用停用"),
    GOODS_CATEGORY_NOT_LEAF(1304, "商品只能挂在叶子分类（无子分类）下"),
    GOODS_WEIGHED_UNIT(1305, "称重商品的计量单位必须为重量单位（BR-GOODS-05）"),
    GOODS_WEIGHED_PLU_REQUIRED(1306, "称重商品必须录入 PLU 码（BR-GOODS-05）"),
    GOODS_BARCODE_CONFIRM_REQUIRED(1307, "条码修改需二次确认"),
    GOODS_CODE_IMMUTABLE(1308, "商品编码创建后不可修改"),
    UNIT_NAME_EXISTS(1311, "计量单位名称已存在"),
    UNIT_HAS_GOODS(1312, "该单位已被商品使用，禁止删除，请改用停用"),
    BRAND_NAME_EXISTS(1321, "品牌名称已存在"),
    PROMO_OVERLAP(1331, "同一商品同一时间段只允许一条生效促销（BR-GOODS-04）"),
    PROMO_PRICE_INVALID(1332, "促销价必须低于零售价"),

    // ---------- INV 库存模块（1400 ~ 1499，FR-INV-01 ~ 12） ----------
    STOCK_NOT_ENOUGH(1401, "库存不足"),
    CHECK_SCOPE_EMPTY(1411, "盘点范围内没有可盘商品"),
    CHECK_OVERLAP(1412, "范围内已存在未审核盘点单，不可重复创建（BR-INV-09）"),
    CHECK_STATUS_INVALID(1413, "盘点单当前状态不允许该操作"),
    CHECK_AUDIT_SELF(1414, "审核人与盘点人必须为不同用户（BR-SYS-01）"),
    CHECK_REASON_REQUIRED(1415, "差异原因或驳回意见不能为空"),
    LOSS_STATUS_INVALID(1421, "报损报溢单当前状态不允许该操作"),
    LOSS_REASON_INVALID(1422, "报损报溢原因不合法或已被停用（数据字典 loss_reason）"),

    // ---------- SALE 销售模块（1500 ~ 1599，FR-SALE-01 ~ 13） ----------
    SALE_GOODS_INVALID(1501, "商品不存在或已停用，不可销售"),
    SALE_GOODS_DUPLICATED(1502, "同一商品在同一单内只能占一行（BR-SALE-01）"),
    SALE_QTY_INVALID(1503, "普通商品数量必须为正整数"),
    SALE_NET_WEIGHT_REQUIRED(1504, "称重商品必须录入净重（最多 3 位小数）"),
    SALE_PAY_TYPE_INVALID(1505, "收款方式不合法"),
    SALE_PAY_AMOUNT_INVALID(1506, "收款金额必须大于 0"),
    SALE_PAID_NOT_ENOUGH(1507, "实收金额不足应收金额"),
    SALE_DISCOUNT_BELOW_MIN(1508, "整单折扣率低于系统下限（BR-SALE-05）"),
    SALE_DISCOUNT_AUTH_REQUIRED(1509, "整单折扣须店长授权（FR-SALE-07）"),
    SALE_DISCOUNT_AUTH_SELF(1516, "折扣/改价授权人不得为当前收银员（FR-SALE-07）"),
    SALE_DISCOUNT_REASON_REQUIRED(1517, "整单折扣须填写原因（FR-SALE-07）"),
    SALE_OVERRIDE_INVALID(1518, "单品改价不合法：仅档案商品可改价，单价须大于 0 且最多 2 位小数（FR-SALE-07）"),
    SALE_OVERRIDE_REASON_REQUIRED(1519, "单品改价须填写原因（FR-SALE-07）"),
    SALE_OVERRIDE_AUTH_REQUIRED(1520, "单品改价须店长授权（FR-SALE-07）"),
    SALE_MEMBER_DISABLED(1510, "会员已停用，请按散客结算（BR-MEM-03）"),
    SALE_MEMBER_NOT_FOUND(1511, "会员不存在"),
    SALE_BALANCE_UNAVAILABLE(1512, "储值支付/退款仅限会员使用"),
    SALE_ORDER_NO_RETRY(1513, "单号生成冲突，请重试结算"),
    SALE_DAILY_NO_EXCEED(1514, "该收银台当日流水号已用尽"),
    SALE_TEMP_LINE_INVALID(1515, "无码商品明细不合法：名称必填、金额须大于 0（FR-SALE-13）"),
    SALE_RETURN_ORDER_NOT_FOUND(1521, "原销售单不存在"),
    SALE_RETURN_FULLY_RETURNED(1522, "该销售单已全部退货"),
    SALE_RETURN_LINE_INVALID(1523, "退货明细不合法：超出可退数量或不属于原单"),
    SALE_RETURN_NOT_ALLOWED(1524, "该商品不允许退货（BR-SALE-07）"),
    SALE_RETURN_EXPIRED(1525, "已超过退货有效期，须店长特批（BR-SALE-05）"),
    SALE_RETURN_AUTH_REQUIRED(1526, "退款超阈值或误操作退货须店长授权（FR-SALE-11）"),
    SALE_RETURN_AUTH_SELF(1527, "授权人不得为退货发起人（FR-SALE-11）"),
    SALE_REFUND_TYPE_INVALID(1528, "退款方式不合法"),
    SALE_RETURN_REASON_INVALID(1529, "退货原因不合法"),
    SHIFT_ALREADY_OPEN(1530, "当前收银员已有在班班次，不可重复开班"),
    SHIFT_NOT_OPEN(1531, "当前收银员没有在班班次，无法交班"),
    SHIFT_NOT_FOUND(1532, "班次不存在"),
    SHIFT_RETURN_FORBIDDEN(1533, "该销售单所属班次已交班，退货须店长处理（BR-SALE-09）"),
    HOLD_ITEM_INVALID(1534, "挂单明细不合法（无码商品名称/金额，档案商品ID，改价仅限档案商品）"),
    HOLD_LIMIT_EXCEED(1535, "未取挂单已达上限（50 张），请先取单或删除"),
    HOLD_NOT_FOUND(1536, "挂单不存在或已被取单/删除"),

    // ---------- 会员模块（FR-MEM-01~08） ----------
    MEMBER_NOT_FOUND(1601, "会员不存在或已被删除"),
    MEMBER_PHONE_EXISTS(1602, "该手机号已注册会员（BR-MEM-01 全局唯一）"),
    MEMBER_PHONE_INVALID(1603, "手机号须为大陆 11 位手机号（BR-MEM-01）"),
    MEMBER_NO_EXCEED(1604, "会员编号流水已用尽，请联系管理员处理"),
    MEMBER_BALANCE_NOT_ENOUGH(1605, "储值余额不足（BR-MEM-07 余额不允许为负）"),
    MEMBER_RECHARGE_AUTH_SELF(1606, "储值充值的授权人不得为发起人本人（FR-MEM-08）"),
    MEMBER_LEVEL_NOT_FOUND(1611, "会员等级不存在"),
    MEMBER_LEVEL_NAME_EXISTS(1612, "等级名称已存在"),
    MEMBER_LEVEL_NO_ENABLED(1613, "没有启用中的会员等级，无法注册会员"),

    // ---------- SUP 供应商模块（1700 ~ 1799，FR-SUP-01 ~ 05） ----------
    SUPPLIER_NOT_FOUND(1701, "供应商不存在"),
    SUPPLIER_NAME_EXISTS(1702, "供应商名称已存在（BR-SUP-01）"),
    SUPPLIER_PHONE_INVALID(1703, "联系电话须为大陆手机号或固话格式（BR-SUP-01）"),
    SUPPLIER_CODE_EXCEED(1704, "供应商编码流水已用尽，请联系管理员处理"),
    SUPPLIER_CREDIT_DAYS_REQUIRED(1705, "结算方式为账期时必须填写账期天数"),
    SUPPLIER_GOODS_INVALID(1711, "供货商品不存在或已删除"),
    SUPPLIER_GOODS_DUPLICATED(1712, "同一商品在供货清单中只能出现一次"),

    // ---------- PUR 采购模块（1800 ~ 1899，FR-PUR-01 ~ 09） ----------
    PUR_ORDER_NOT_FOUND(1801, "采购单不存在"),
    PUR_STATUS_INVALID(1802, "采购单当前状态不允许该操作（BR-PUR-02 状态机约束）"),
    PUR_LINE_INVALID(1803, "采购明细不合法：数量须大于 0、单价须大于等于 0（BR-PUR-03）"),
    PUR_LINE_DUPLICATED(1804, "同一商品在同一采购单内只能占一行"),
    PUR_WITHDRAW_NOT_OWNER(1805, "仅创建人可撤回已提交的采购单（FR-PUR-04）"),
    PUR_REASON_REQUIRED(1806, "作废/终止/关闭原因不能为空（FR-PUR-05）"),
    PUR_SETTLE_INVALID(1807, "结算登记不合法：单据状态或结算参数有误"),
    PUR_NO_EXCEED(1808, "当日采购单流水号已用尽，请明日再试或联系管理员"),
    PUR_RECEIPT_LINE_INVALID(1811, "入库明细不合法：实收数量须大于 0 或超出待入库范围"),
    PUR_RECEIPT_TOLERANCE_EXCEED(1812, "实收数量超出超收容差，须店长授权（BR-PUR-04）"),
    PUR_RECEIPT_PRICE_AUTH(1813, "实收单价与采购单价偏离超容差，须店长授权（BR-PUR-05）"),
    PUR_RECEIPT_DUTY_SEPARATION(1814, "入库验收人与采购单创建人须为不同用户（BR-SYS-01）"),
    PUR_RETURN_LINE_INVALID(1821, "退货明细不合法：超出可退数量或不属于原采购单（FR-PUR-08）"),
    PUR_RETURN_REASON_REQUIRED(1822, "退货原因不能为空（FR-PUR-08）"),
    PUR_RETURN_STATUS_INVALID(1823, "采购退货单当前状态不允许该操作"),
    PUR_SUGGEST_NO_SUPPLIER(1831, "所选商品缺少默认供应商，无法生成采购单（FR-PUR-02）");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
