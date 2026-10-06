-- =====================================================================
-- 超市商品管理系统（SMMS）数据库建表脚本
-- 依据：《需求规格说明书-精简版》第 6 章数据需求（DR-01 ~ DR-38）
-- 环境：MySQL 8.0+ / MariaDB 10.6+（InnoDB、utf8mb4）
-- 约定：
--   * 金额 decimal(12,2)；成本单价 decimal(12,4)；折扣率 decimal(5,4)
--   * 数量：普通商品 int/decimal(10,3)；称重商品 decimal(10,3)（DR-C-07）
--   * 禁止 float/double 存储金额；时间精度到秒；时区 Asia/Shanghai（C-08）
--   * 所有业务表统一含审计字段（DR-C-08）
--   * 只增表（库存流水/积分流水/储值流水/操作日志/登录日志）不提供
--     UPDATE/DELETE 路径（DR-C-06）
--   * store_id 为多门店扩展预留字段，本期恒为默认值 1（NFR-EXT-02）
-- =====================================================================

CREATE DATABASE IF NOT EXISTS smms DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE smms;

SET NAMES utf8mb4;

-- =====================================================================
-- 一、系统与权限（DR-28 ~ DR-36）
-- =====================================================================

-- 用户表（DR-28）：用户不可物理删除，只可停用（FR-SYS-02）
CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT UNSIGNED AUTO_INCREMENT COMMENT '用户ID',
    username      VARCHAR(30)  NOT NULL COMMENT '用户名（登录账号）',
    password      VARCHAR(100) NOT NULL COMMENT '密码哈希（BCrypt 强度10，BR-SYS-02）',
    real_name     VARCHAR(30)  NOT NULL COMMENT '真实姓名',
    phone         VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1启用 0停用',
    last_login_time DATETIME   DEFAULT NULL COMMENT '最后登录时间',
    fail_count    INT          NOT NULL DEFAULT 0 COMMENT '连续登录失败次数（FR-SYS-01）',
    lock_until    DATETIME     DEFAULT NULL COMMENT '锁定到期时间（FR-SYS-01）',
    must_change_pwd TINYINT    NOT NULL DEFAULT 0 COMMENT '是否强制下次登录修改密码（重置密码后）',
    remark        VARCHAR(200) DEFAULT NULL COMMENT '备注',
    create_by     VARCHAR(30)  DEFAULT NULL COMMENT '创建人',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by     VARCHAR(30)  DEFAULT NULL COMMENT '更新人',
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB COMMENT = '系统用户';

-- 角色表（DR-29）：5 个内置角色不可删除（FR-SYS-04）
CREATE TABLE IF NOT EXISTS sys_role (
    id          BIGINT UNSIGNED AUTO_INCREMENT COMMENT '角色ID',
    role_name   VARCHAR(30)  NOT NULL COMMENT '角色名称',
    role_code   VARCHAR(30)  NOT NULL COMMENT '角色代码（SUPER_ADMIN等）',
    description VARCHAR(200) DEFAULT NULL COMMENT '描述',
    is_builtin  TINYINT      NOT NULL DEFAULT 0 COMMENT '是否内置角色：1是 0否',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1启用 0停用',
    create_by   VARCHAR(30)  DEFAULT NULL COMMENT '创建人',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by   VARCHAR(30)  DEFAULT NULL COMMENT '更新人',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (role_code)
) ENGINE = InnoDB COMMENT = '系统角色';

-- 权限表（DR-30）：目录/菜单/按钮（接口）三级粒度（FR-SYS-05）
CREATE TABLE IF NOT EXISTS sys_permission (
    id          BIGINT UNSIGNED AUTO_INCREMENT COMMENT '权限ID',
    parent_id   BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '父权限ID，0=根',
    name        VARCHAR(30)  NOT NULL COMMENT '权限名称',
    perm_type   CHAR(1)      NOT NULL COMMENT '类型：D目录 M菜单 B按钮',
    perm_code   VARCHAR(60)  DEFAULT NULL COMMENT '权限标识（如 goods:create），目录/菜单为空',
    route_path  VARCHAR(100) DEFAULT NULL COMMENT '前端路由路径',
    component   VARCHAR(100) DEFAULT NULL COMMENT '前端组件路径（懒加载）',
    icon        VARCHAR(50)  DEFAULT NULL COMMENT '菜单图标',
    sort_no     INT          NOT NULL DEFAULT 0 COMMENT '同级排序号',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1启用 0停用',
    create_by   VARCHAR(30)  DEFAULT NULL COMMENT '创建人',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by   VARCHAR(30)  DEFAULT NULL COMMENT '更新人',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_perm_code (perm_code)
) ENGINE = InnoDB COMMENT = '系统权限（目录/菜单/按钮树）';

-- 用户角色关联表（DR-31）
CREATE TABLE IF NOT EXISTS sys_user_role (
    id      BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    role_id BIGINT UNSIGNED NOT NULL COMMENT '角色ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (user_id, role_id),
    KEY idx_role (role_id)
) ENGINE = InnoDB COMMENT = '用户-角色关联';

-- 角色权限关联表（DR-32）
CREATE TABLE IF NOT EXISTS sys_role_permission (
    id       BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
    role_id  BIGINT UNSIGNED NOT NULL COMMENT '角色ID',
    perm_id  BIGINT UNSIGNED NOT NULL COMMENT '权限ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_perm (role_id, perm_id),
    KEY idx_perm (perm_id)
) ENGINE = InnoDB COMMENT = '角色-权限关联';

-- Token 黑名单（IR-SW-03：登出、停用、权限变更即时失效）
CREATE TABLE IF NOT EXISTS sys_token_blacklist (
    jti         VARCHAR(40)  NOT NULL COMMENT 'JWT ID',
    expire_time DATETIME     NOT NULL COMMENT 'Token 原始过期时间（过期后可清理）',
    reason      VARCHAR(50)  DEFAULT NULL COMMENT '拉黑原因：LOGOUT/DISABLE/PERM_CHANGE',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (jti),
    KEY idx_expire (expire_time)
) ENGINE = InnoDB COMMENT = 'JWT 黑名单';

-- 系统参数表（DR-35，FR-SYS-06）：键值对业务参数
CREATE TABLE IF NOT EXISTS sys_config (
    id            BIGINT UNSIGNED AUTO_INCREMENT COMMENT '参数ID',
    config_name   VARCHAR(60)  NOT NULL COMMENT '参数名称',
    config_key    VARCHAR(60)  NOT NULL COMMENT '参数键',
    config_value  VARCHAR(500) NOT NULL COMMENT '参数值',
    value_type    VARCHAR(10)  NOT NULL DEFAULT 'STRING' COMMENT '值类型：STRING/NUMBER/BOOLEAN/TIME',
    config_group  VARCHAR(30)  NOT NULL DEFAULT 'basic' COMMENT '参数分组',
    editable      TINYINT      NOT NULL DEFAULT 1 COMMENT '是否可修改：1是 0否',
    instant_effect TINYINT     NOT NULL DEFAULT 1 COMMENT '是否立即生效：1是 0否',
    remark        VARCHAR(200) DEFAULT NULL COMMENT '备注',
    update_by     VARCHAR(30)  DEFAULT NULL COMMENT '更新人',
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    create_by     VARCHAR(30)  DEFAULT NULL COMMENT '创建人',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_config_key (config_key)
) ENGINE = InnoDB COMMENT = '系统参数';

-- 数据字典类型表（DR-36，FR-SYS-07）
CREATE TABLE IF NOT EXISTS sys_dict (
    id          BIGINT UNSIGNED AUTO_INCREMENT COMMENT '字典ID',
    dict_type   VARCHAR(30)  NOT NULL COMMENT '字典类型编码（如 pay_type）',
    dict_name   VARCHAR(30)  NOT NULL COMMENT '字典名称',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1启用 0停用',
    remark      VARCHAR(200) DEFAULT NULL COMMENT '备注',
    create_by   VARCHAR(30)  DEFAULT NULL COMMENT '创建人',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by   VARCHAR(30)  DEFAULT NULL COMMENT '更新人',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dict_type (dict_type)
) ENGINE = InnoDB COMMENT = '数据字典类型';

-- 数据字典项表（DR-36）：单据存代码，展示时关联字典（FR-SYS-07）
CREATE TABLE IF NOT EXISTS sys_dict_item (
    id          BIGINT UNSIGNED AUTO_INCREMENT COMMENT '字典项ID',
    dict_id     BIGINT UNSIGNED NOT NULL COMMENT '字典ID',
    label       VARCHAR(30)  NOT NULL COMMENT '标签（显示名）',
    value       VARCHAR(30)  NOT NULL COMMENT '值（存储代码）',
    sort_no     INT          NOT NULL DEFAULT 0 COMMENT '排序号',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1启用 0停用（停用项不可被新单据选择）',
    remark      VARCHAR(200) DEFAULT NULL COMMENT '备注',
    create_by   VARCHAR(30)  DEFAULT NULL COMMENT '创建人',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by   VARCHAR(30)  DEFAULT NULL COMMENT '更新人',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dict_value (dict_id, value)
) ENGINE = InnoDB COMMENT = '数据字典项';

-- 操作日志（DR-33，FR-SYS-08）：只增不改不删，保留≥1年
CREATE TABLE IF NOT EXISTS operation_log (
    id            BIGINT UNSIGNED AUTO_INCREMENT COMMENT '日志ID',
    user_id       BIGINT UNSIGNED DEFAULT NULL COMMENT '操作人ID',
    username      VARCHAR(30)  DEFAULT NULL COMMENT '操作人用户名',
    role_names    VARCHAR(100) DEFAULT NULL COMMENT '操作人角色（BR-SYS-07 同时记录）',
    module        VARCHAR(30)  NOT NULL COMMENT '模块（如 商品管理）',
    action        VARCHAR(60)  NOT NULL COMMENT '动作（如 调价、审核）',
    target        VARCHAR(60)  DEFAULT NULL COMMENT '目标对象标识（单号/编码等）',
    http_method   VARCHAR(10)  DEFAULT NULL COMMENT '请求方法',
    request_url   VARCHAR(200) DEFAULT NULL COMMENT '请求URL',
    param_summary VARCHAR(1000) DEFAULT NULL COMMENT '请求参数摘要（不含敏感信息）',
    ip            VARCHAR(50)  DEFAULT NULL COMMENT '客户端IP',
    user_agent    VARCHAR(255) DEFAULT NULL COMMENT '浏览器标识',
    result        TINYINT      NOT NULL DEFAULT 1 COMMENT '结果：1成功 0失败',
    error_msg     VARCHAR(500) DEFAULT NULL COMMENT '错误信息',
    cost_ms       INT          DEFAULT NULL COMMENT '耗时（毫秒）',
    operate_time  DATETIME     NOT NULL COMMENT '操作时间',
    PRIMARY KEY (id),
    KEY idx_op_time (operate_time),
    KEY idx_op_user (username, operate_time),
    KEY idx_op_module (module)
) ENGINE = InnoDB COMMENT = '操作日志（只增不改不删）';

-- 登录日志（DR-34，FR-SYS-01）
CREATE TABLE IF NOT EXISTS login_log (
    id          BIGINT UNSIGNED AUTO_INCREMENT COMMENT '日志ID',
    username    VARCHAR(30)  NOT NULL COMMENT '尝试登录的用户名',
    user_id     BIGINT UNSIGNED DEFAULT NULL COMMENT '用户ID（登录成功时）',
    ip          VARCHAR(50)  DEFAULT NULL COMMENT '客户端IP',
    browser     VARCHAR(50)  DEFAULT NULL COMMENT '浏览器',
    os          VARCHAR(50)  DEFAULT NULL COMMENT '操作系统',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1成功 0失败',
    message     VARCHAR(100) DEFAULT NULL COMMENT '提示信息（失败原因/锁定提示）',
    login_time  DATETIME     NOT NULL COMMENT '登录时间',
    PRIMARY KEY (id),
    KEY idx_ll_time (login_time),
    KEY idx_ll_username (username, login_time)
) ENGINE = InnoDB COMMENT = '登录日志（只增不改不删）';

-- =====================================================================
-- 二、主数据：分类 / 品牌 / 单位 / 商品（DR-01 ~ DR-04、DR-26、DR-27）
-- =====================================================================

-- 商品分类表（DR-01）：树形结构，最多 3 级（BR-CAT-04）
CREATE TABLE IF NOT EXISTS category (
    id          BIGINT UNSIGNED AUTO_INCREMENT COMMENT '分类ID',
    parent_id   BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '父分类ID，0=根节点',
    name        VARCHAR(30)  NOT NULL COMMENT '分类名称（同级唯一，BR-CAT-01）',
    level       TINYINT      NOT NULL DEFAULT 1 COMMENT '层级：1~3',
    path         VARCHAR(50)  NOT NULL DEFAULT '' COMMENT '物化路径，如 1/5/12',
    sort_no     INT          NOT NULL DEFAULT 0 COMMENT '同级排序号',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1启用 0停用',
    create_by   VARCHAR(30)  DEFAULT NULL COMMENT '创建人',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by   VARCHAR(30)  DEFAULT NULL COMMENT '更新人',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_parent_name (parent_id, name),
    KEY idx_parent (parent_id)
) ENGINE = InnoDB COMMENT = '商品分类（树形，最多3级）';

-- 品牌表（DR-03，FR-GOODS-13 Could）
CREATE TABLE IF NOT EXISTS brand (
    id          BIGINT UNSIGNED AUTO_INCREMENT COMMENT '品牌ID',
    name        VARCHAR(30)  NOT NULL COMMENT '品牌名称（唯一）',
    first_letter VARCHAR(2) DEFAULT NULL COMMENT '首字母（拼音）',
    logo        VARCHAR(255) DEFAULT NULL COMMENT 'Logo 路径',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1启用 0停用',
    create_by   VARCHAR(30)  DEFAULT NULL COMMENT '创建人',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by   VARCHAR(30)  DEFAULT NULL COMMENT '更新人',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_brand_name (name)
) ENGINE = InnoDB COMMENT = '商品品牌';

-- 计量单位表（DR-04，FR-GOODS-08）
CREATE TABLE IF NOT EXISTS unit (
    id          BIGINT UNSIGNED AUTO_INCREMENT COMMENT '单位ID',
    name        VARCHAR(10)  NOT NULL COMMENT '单位名称（如 瓶/袋/kg）',
    symbol      VARCHAR(10)  DEFAULT NULL COMMENT '单位符号',
    is_weight   TINYINT      NOT NULL DEFAULT 0 COMMENT '是否重量单位：1是 0否（称重商品只能选重量单位，BR-GOODS-05）',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1启用 0停用',
    create_by   VARCHAR(30)  DEFAULT NULL COMMENT '创建人',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by   VARCHAR(30)  DEFAULT NULL COMMENT '更新人',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_unit_name (name)
) ENGINE = InnoDB COMMENT = '计量单位';

-- 商品表（DR-02）：核心主数据，一 SKU 一条；不可物理删除（BR-GOODS-06）
CREATE TABLE IF NOT EXISTS goods (
    id                BIGINT UNSIGNED AUTO_INCREMENT COMMENT '商品ID',
    goods_code        VARCHAR(20)  NOT NULL COMMENT '商品编码：分类码+流水（BR-GOODS-02），创建后不可修改',
    barcode           VARCHAR(20)  NOT NULL COMMENT '商品条码（EAN-13/店内码，全局唯一，BR-GOODS-01）',
    name              VARCHAR(100) NOT NULL COMMENT '商品名称',
    pinyin_initial    VARCHAR(100) DEFAULT NULL COMMENT '名称拼音首字母（随名称自动生成，收银检索 FR-SALE-02）',
    category_id       BIGINT UNSIGNED NOT NULL COMMENT '所属分类（必须是叶子分类，BR-CAT-04）',
    brand_id          BIGINT UNSIGNED DEFAULT NULL COMMENT '品牌ID',
    unit_id           BIGINT UNSIGNED NOT NULL COMMENT '计量单位ID',
    spec              VARCHAR(50)  DEFAULT NULL COMMENT '规格',
    origin            VARCHAR(100) DEFAULT NULL COMMENT '产地',
    purchase_price    DECIMAL(12,2) DEFAULT NULL COMMENT '进货参考价 ≥0',
    retail_price      DECIMAL(12,2) NOT NULL COMMENT '零售单价 >0',
    member_price      DECIMAL(12,2) DEFAULT NULL COMMENT '会员价 ≤零售价',
    plu_code          VARCHAR(10)  DEFAULT NULL COMMENT 'PLU 码（称重商品必填，BR-GOODS-05）',
    is_weighed        TINYINT      NOT NULL DEFAULT 0 COMMENT '是否称重商品：1是 0否',
    allow_return      TINYINT      NOT NULL DEFAULT 1 COMMENT '是否允许退货：1是 0否（BR-SALE-07）',
    safety_stock_lower DECIMAL(10,3) NOT NULL DEFAULT 0 COMMENT '安全库存下限',
    safety_stock_upper DECIMAL(10,3) DEFAULT NULL COMMENT '库存上限（积压预警）',
    image             VARCHAR(255) DEFAULT NULL COMMENT '图片相对路径',
    status            TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1启用 0停用',
    remark            VARCHAR(500) DEFAULT NULL COMMENT '备注',
    create_by         VARCHAR(30)  DEFAULT NULL COMMENT '创建人',
    create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by         VARCHAR(30)  DEFAULT NULL COMMENT '更新人',
    update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_goods_code (goods_code),
    UNIQUE KEY uk_barcode (barcode),
    KEY idx_goods_name (name),
    KEY idx_goods_category (category_id),
    KEY idx_goods_status (status)
) ENGINE = InnoDB COMMENT = '商品档案';

-- 调价历史（DR-26，FR-GOODS-10）
CREATE TABLE IF NOT EXISTS price_history (
    id             BIGINT UNSIGNED AUTO_INCREMENT COMMENT '记录ID',
    goods_id       BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
    price_type     VARCHAR(10)  NOT NULL COMMENT '价格类型：RETAIL零售/MEMBER会员/PURCHASE进货',
    old_value      DECIMAL(12,2) DEFAULT NULL COMMENT '前值（新设价格时为空）',
    new_value      DECIMAL(12,2) DEFAULT NULL COMMENT '后值（清空价格时为空）',
    effective_time DATETIME     DEFAULT NULL COMMENT '生效时间',
    operator_name  VARCHAR(30)  NOT NULL COMMENT '操作人',
    reason         VARCHAR(200) DEFAULT NULL COMMENT '调价原因',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (id),
    KEY idx_ph_goods (goods_id, create_time)
) ENGINE = InnoDB COMMENT = '调价历史';

-- 促销表（DR-27，FR-GOODS-09）：同一商品同一时间段仅一条生效促销（BR-GOODS-04，应用层校验）
CREATE TABLE IF NOT EXISTS promotion (
    id          BIGINT UNSIGNED AUTO_INCREMENT COMMENT '促销ID',
    goods_id    BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
    promo_price DECIMAL(12,2) NOT NULL COMMENT '促销价（<零售价）',
    start_time  DATETIME     NOT NULL COMMENT '开始时间',
    end_time    DATETIME     NOT NULL COMMENT '结束时间',
    status      VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE生效/EXPIRED过期/DISABLED停用',
    create_by   VARCHAR(30)  DEFAULT NULL COMMENT '创建人',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by   VARCHAR(30)  DEFAULT NULL COMMENT '更新人',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    remark      VARCHAR(200) DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (id),
    KEY idx_promo_goods (goods_id, status),
    KEY idx_promo_time (end_time)
) ENGINE = InnoDB COMMENT = '单品限时促销';

-- =====================================================================
-- 三、供应商与采购（DR-05 ~ DR-10）
-- =====================================================================

-- 供应商表（DR-05）
CREATE TABLE IF NOT EXISTS supplier (
    id               BIGINT UNSIGNED AUTO_INCREMENT COMMENT '供应商ID',
    supplier_code    VARCHAR(10)  NOT NULL COMMENT '供应商编码：S+5位流水（BR-SUP-03）',
    name             VARCHAR(100) NOT NULL COMMENT '供应商名称（唯一，BR-SUP-01）',
    contact_person   VARCHAR(30)  DEFAULT NULL COMMENT '联系人',
    phone            VARCHAR(20)  DEFAULT NULL COMMENT '联系电话（手机/固话格式）',
    address          VARCHAR(200) DEFAULT NULL COMMENT '地址',
    email            VARCHAR(50)  DEFAULT NULL COMMENT '邮箱',
    bank_name        VARCHAR(100) DEFAULT NULL COMMENT '开户行（仅店长及以上可见，BR-SUP-01）',
    bank_account     VARCHAR(50)  DEFAULT NULL COMMENT '银行账号（脱敏展示，FR-SYS-09）',
    tax_no           VARCHAR(30)  DEFAULT NULL COMMENT '纳税人识别号',
    settle_type      VARCHAR(10)  DEFAULT 'CASH' COMMENT '结算方式（字典 settle_type）：CASH现结/MONTHLY月结/CREDIT账期',
    credit_days      INT          DEFAULT NULL COMMENT '账期天数（结算方式为账期时）',
    lead_time_days   INT          NOT NULL DEFAULT 3 COMMENT '采购提前期（天），参与建议采购量（BR-INV-07）',
    total_purchase_amount DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '累计采购额（派生数据，DR-C-04）',
    status           TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1启用 0停用',
    remark           VARCHAR(500) DEFAULT NULL COMMENT '备注',
    create_by        VARCHAR(30)  DEFAULT NULL COMMENT '创建人',
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by        VARCHAR(30)  DEFAULT NULL COMMENT '更新人',
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_supplier_code (supplier_code),
    UNIQUE KEY uk_supplier_name (name),
    KEY idx_supplier_status (status)
) ENGINE = InnoDB COMMENT = '供应商档案';

-- 供应商-商品关联表（DR-06，FR-SUP-04）
CREATE TABLE IF NOT EXISTS supplier_goods (
    id               BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
    supplier_id      BIGINT UNSIGNED NOT NULL COMMENT '供应商ID',
    goods_id         BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
    supply_price     DECIMAL(12,2) DEFAULT NULL COMMENT '供货单价',
    is_default       TINYINT      NOT NULL DEFAULT 0 COMMENT '是否默认供应商：1是 0否',
    last_supply_time DATETIME     DEFAULT NULL COMMENT '最近供货时间',
    create_by        VARCHAR(30)  DEFAULT NULL COMMENT '创建人',
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by        VARCHAR(30)  DEFAULT NULL COMMENT '更新人',
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_supplier_goods (supplier_id, goods_id),
    KEY idx_sg_goods (goods_id)
) ENGINE = InnoDB COMMENT = '供应商-商品关联及供货价';

-- 采购单（DR-07）：状态机见 §2.2.1
CREATE TABLE IF NOT EXISTS purchase_order (
    id              BIGINT UNSIGNED AUTO_INCREMENT COMMENT '采购单ID',
    order_no        VARCHAR(20)  NOT NULL COMMENT '采购单号：PO+yyyyMMdd+4位流水（BR-PUR-01）',
    store_id        BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '门店ID（多门店预留，本期恒1，NFR-EXT-02）',
    supplier_id     BIGINT UNSIGNED NOT NULL COMMENT '供应商ID',
    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT草稿/SUBMITTED已提交/APPROVED已审核待入库/PARTIAL部分入库/COMPLETED已完成/TERMINATED已终止/CANCELLED已作废/PARTIAL_RETURNED部分退货/ALL_RETURNED全部退货',
    create_by       VARCHAR(30)  NOT NULL COMMENT '创建人（采购员）',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    submit_time     DATETIME     DEFAULT NULL COMMENT '提交时间',
    audit_by        VARCHAR(30)  DEFAULT NULL COMMENT '审核人（店长）',
    audit_time      DATETIME     DEFAULT NULL COMMENT '审核时间',
    audit_remark    VARCHAR(200) DEFAULT NULL COMMENT '审核意见（驳回时必填）',
    expect_date     DATE         DEFAULT NULL COMMENT '预计到货日期',
    total_amount    DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '订购总额 = Σ(数量×单价)（BR-PUR-03）',
    received_amount DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '实收总额（按实收单价计算）',
    return_amount   DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '退货总额',
    settle_status   VARCHAR(10)  NOT NULL DEFAULT 'UNSETTLED' COMMENT '结算状态：UNSETTLED未结算/PARTIAL部分结算/SETTLED已结算',
    settle_amount   DECIMAL(12,2) DEFAULT NULL COMMENT '已结算金额',
    settle_method   VARCHAR(20)  DEFAULT NULL COMMENT '结算方式（字典 settle_type）：CASH现结/MONTHLY月结/CREDIT账期（FR-PUR-09）',
    settle_date     DATE         DEFAULT NULL COMMENT '结算日期',
    settle_by       VARCHAR(30)  DEFAULT NULL COMMENT '结算经办人',
    terminate_reason VARCHAR(200) DEFAULT NULL COMMENT '终止/作废原因',
    version         INT          NOT NULL DEFAULT 0 COMMENT '版本号（乐观锁，BR-PUR-06）',
    remark          VARCHAR(500) DEFAULT NULL COMMENT '备注',
    update_by       VARCHAR(30)  DEFAULT NULL COMMENT '更新人',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_po_no (order_no),
    KEY idx_po_supplier (supplier_id),
    KEY idx_po_status (status),
    KEY idx_po_create_time (create_time)
) ENGINE = InnoDB COMMENT = '采购单';

-- 采购单明细（DR-08）
CREATE TABLE IF NOT EXISTS purchase_order_item (
    id           BIGINT UNSIGNED AUTO_INCREMENT COMMENT '明细ID',
    order_id     BIGINT UNSIGNED NOT NULL COMMENT '采购单ID',
    goods_id     BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
    quantity     DECIMAL(10,3) NOT NULL COMMENT '订购数量 >0（BR-PUR-03）',
    price        DECIMAL(12,2) NOT NULL COMMENT '采购单价 ≥0',
    amount       DECIMAL(12,2) NOT NULL COMMENT '行金额 = 数量×单价',
    received_qty DECIMAL(10,3) NOT NULL DEFAULT 0 COMMENT '已入库数量',
    returned_qty DECIMAL(10,3) NOT NULL DEFAULT 0 COMMENT '已退货数量',
    remark       VARCHAR(200) DEFAULT NULL COMMENT '行备注',
    PRIMARY KEY (id),
    KEY idx_poi_order (order_id),
    KEY idx_poi_goods (goods_id)
) ENGINE = InnoDB COMMENT = '采购单明细';

-- 入库单（DR-09，FR-PUR-06）
CREATE TABLE IF NOT EXISTS receipt_order (
    id               BIGINT UNSIGNED AUTO_INCREMENT COMMENT '入库单ID',
    order_no         VARCHAR(20)  NOT NULL COMMENT '入库单号：RK+yyyyMMdd+4位流水（BR-PUR-01）',
    store_id         BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '门店ID（预留）',
    purchase_order_id BIGINT UNSIGNED NOT NULL COMMENT '采购单ID',
    supplier_id      BIGINT UNSIGNED NOT NULL COMMENT '供应商ID',
    receipt_time     DATETIME     NOT NULL COMMENT '入库时间',
    operator_name    VARCHAR(30)  NOT NULL COMMENT '验收操作人（库管员）',
    authorizer_name  VARCHAR(30)  DEFAULT NULL COMMENT '授权人（超收/价差授权时记录，BR-PUR-04/05）',
    delivery_order_no VARCHAR(50) DEFAULT NULL COMMENT '供应商送货单号',
    total_amount     DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '入库总金额 = Σ(实收数量×实收单价)',
    remark           VARCHAR(500) DEFAULT NULL COMMENT '备注',
    request_token    VARCHAR(64)  DEFAULT NULL COMMENT '请求令牌（提交防重，NFR-REL-02）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ro_no (order_no),
    UNIQUE KEY uk_ro_token (request_token),
    KEY idx_ro_po (purchase_order_id),
    KEY idx_ro_supplier (supplier_id),
    KEY idx_ro_time (receipt_time)
) ENGINE = InnoDB COMMENT = '采购入库单';

-- 入库单明细（DR-10）
CREATE TABLE IF NOT EXISTS receipt_order_item (
    id               BIGINT UNSIGNED AUTO_INCREMENT COMMENT '明细ID',
    receipt_id       BIGINT UNSIGNED NOT NULL COMMENT '入库单ID',
    purchase_item_id BIGINT UNSIGNED DEFAULT NULL COMMENT '采购单明细ID',
    goods_id         BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
    received_qty     DECIMAL(10,3) NOT NULL COMMENT '实收数量（受容差约束，BR-PUR-04）',
    received_price   DECIMAL(12,2) NOT NULL COMMENT '实收单价（存货成本以此为准，BR-PUR-07）',
    amount           DECIMAL(12,2) NOT NULL COMMENT '行金额 = 实收数量×实收单价',
    remark           VARCHAR(200) DEFAULT NULL COMMENT '验收备注',
    PRIMARY KEY (id),
    KEY idx_roi_receipt (receipt_id),
    KEY idx_roi_goods (goods_id)
) ENGINE = InnoDB COMMENT = '采购入库单明细';

-- 采购退货单（FR-PUR-08）：PT+yyyyMMdd+4位流水（BR-PUR-01）；店长审核通过后事务内执行退货
CREATE TABLE IF NOT EXISTS purchase_return_order (
    id                BIGINT UNSIGNED AUTO_INCREMENT COMMENT '退货单ID',
    order_no          VARCHAR(20)  NOT NULL COMMENT '退货单号：PT+yyyyMMdd+4位流水（BR-PUR-01）',
    store_id          BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '门店ID（预留）',
    purchase_order_id BIGINT UNSIGNED NOT NULL COMMENT '原采购单ID',
    supplier_id       BIGINT UNSIGNED NOT NULL COMMENT '供应商ID',
    status            VARCHAR(20)  NOT NULL DEFAULT 'SUBMITTED' COMMENT '状态：SUBMITTED待审核/APPROVED已退货/REJECTED已驳回',
    return_reason     VARCHAR(200) NOT NULL COMMENT '退货原因（质量问题等）',
    total_amount      DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '退货总额 = Σ(退货数量×入库时成本单价)',
    create_by         VARCHAR(30)  NOT NULL COMMENT '创建人（采购员）',
    create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    audit_by          VARCHAR(30)  DEFAULT NULL COMMENT '审核人（店长）',
    audit_time        DATETIME     DEFAULT NULL COMMENT '审核时间',
    audit_remark      VARCHAR(200) DEFAULT NULL COMMENT '审核意见',
    remark            VARCHAR(500) DEFAULT NULL COMMENT '备注',
    update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_pr_no (order_no),
    KEY idx_pr_po (purchase_order_id),
    KEY idx_pr_supplier (supplier_id),
    KEY idx_pr_status (status)
) ENGINE = InnoDB COMMENT = '采购退货单';

-- 采购退货单明细（FR-PUR-08）：退货数量 ≤ 已入库数量 − 已退数量
CREATE TABLE IF NOT EXISTS purchase_return_item (
    id                BIGINT UNSIGNED AUTO_INCREMENT COMMENT '明细ID',
    return_id         BIGINT UNSIGNED NOT NULL COMMENT '退货单ID',
    purchase_item_id  BIGINT UNSIGNED NOT NULL COMMENT '采购单明细ID',
    goods_id          BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
    return_qty        DECIMAL(10,3) NOT NULL COMMENT '退货数量',
    return_price      DECIMAL(12,2) NOT NULL COMMENT '退货单价（入库时成本单价，按入库记录加权）',
    amount            DECIMAL(12,2) NOT NULL COMMENT '行金额 = 退货数量×退货单价',
    remark            VARCHAR(200) DEFAULT NULL COMMENT '行备注',
    PRIMARY KEY (id),
    KEY idx_pri_return (return_id),
    KEY idx_pri_goods (goods_id)
) ENGINE = InnoDB COMMENT = '采购退货单明细';

-- =====================================================================
-- 四、库存（DR-11 ~ DR-15、DR-38）
-- =====================================================================

-- 库存结存表（DR-11）：与商品 1:1，派生数据（BR-INV-11 禁止直接修改）
CREATE TABLE IF NOT EXISTS inventory (
    goods_id        BIGINT UNSIGNED NOT NULL COMMENT '商品ID（主键，与商品1:1）',
    store_id        BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '门店ID（预留）',
    quantity        DECIMAL(10,3) NOT NULL DEFAULT 0 COMMENT '结存数量（可为负仅当允许负库存，BR-INV-02）',
    cost_price      DECIMAL(12,4) NOT NULL DEFAULT 0 COMMENT '成本单价（移动加权平均，BR-INV-06）',
    cost_amount     DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '库存成本金额 = 结存×成本单价',
    last_change_time DATETIME    DEFAULT NULL COMMENT '最近变动时间',
    version         INT         NOT NULL DEFAULT 0 COMMENT '版本号（乐观锁，BR-INV-04）',
    update_time     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (goods_id),
    KEY idx_inv_store (store_id)
) ENGINE = InnoDB COMMENT = '库存结存（派生数据，只能由流水驱动）';

-- 库存流水表（DR-12）：不可变，只增不改不删（BR-INV-04）
CREATE TABLE IF NOT EXISTS inventory_record (
    id            BIGINT UNSIGNED AUTO_INCREMENT COMMENT '流水ID',
    goods_id      BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
    store_id      BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '门店ID（预留）',
    record_type   VARCHAR(20)  NOT NULL COMMENT '变动类型：INIT/PURCHASE_IN/PURCHASE_RETURN/SALE_OUT/SALE_RETURN/CHECK_SURPLUS/CHECK_SHORTAGE/LOSS_OUT/OVERFLOW_IN（§8.2）',
    change_qty    DECIMAL(10,3) NOT NULL COMMENT '变动数量（带符号，入库+出库-，BR-INV-05）',
    before_qty    DECIMAL(10,3) NOT NULL COMMENT '变动前结存',
    after_qty     DECIMAL(10,3) NOT NULL COMMENT '变动后结存',
    unit_cost     DECIMAL(12,4) NOT NULL DEFAULT 0 COMMENT '单位成本（出库为结转成本，入库为入库单价）',
    biz_type      VARCHAR(20)  DEFAULT NULL COMMENT '关联单据类型：PO/RK/PT/XS/XT/PD/SZ/BY',
    biz_no        VARCHAR(20)  DEFAULT NULL COMMENT '关联单据号',
    batch_no      VARCHAR(30)  DEFAULT NULL COMMENT '批次号（预留，本期恒空，NFR-EXT-06）',
    operator_name VARCHAR(30)  NOT NULL COMMENT '操作人',
    operate_time  DATETIME     NOT NULL COMMENT '操作时间',
    remark        VARCHAR(200) DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (id),
    KEY idx_ir_goods_time (goods_id, operate_time),
    KEY idx_ir_biz_no (biz_no),
    KEY idx_ir_type_time (record_type, operate_time),
    KEY idx_ir_time (operate_time)
) ENGINE = InnoDB COMMENT = '库存流水（不可变，审计与成本核算核心）';

-- 盘点单（DR-13，FR-INV-07）：状态机见 §2.2.4
CREATE TABLE IF NOT EXISTS stock_check_order (
    id                  BIGINT UNSIGNED AUTO_INCREMENT COMMENT '盘点单ID',
    order_no            VARCHAR(20)  NOT NULL COMMENT '盘点单号：PD+yyyyMMdd+4位流水',
    store_id            BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '门店ID（预留）',
    scope_type          VARCHAR(10)  NOT NULL COMMENT '盘点范围：ALL全店/CATEGORY分类/GOODS指定商品/WARNING预警商品',
    scope_desc          VARCHAR(200) DEFAULT NULL COMMENT '范围描述',
    status              VARCHAR(20)  NOT NULL DEFAULT 'CHECKING' COMMENT '状态：CHECKING盘点中/PENDING_AUDIT待审核/AUDITED已审核/CANCELLED已作废',
    checker_name        VARCHAR(30)  NOT NULL COMMENT '盘点人',
    start_time          DATETIME     NOT NULL COMMENT '开始时间',
    base_time           DATETIME     NOT NULL COMMENT '账面快照基准时间（BR-INV-10）',
    item_count          INT          NOT NULL DEFAULT 0 COMMENT '盘点商品行数',
    surplus_amount       DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '盘盈金额',
    shortage_amount     DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '盘亏金额',
    during_change_count INT          NOT NULL DEFAULT 0 COMMENT '盘点期间发生出入库笔数（BR-INV-10）',
    auditor_name        VARCHAR(30)  DEFAULT NULL COMMENT '审核人（店长，须≠盘点人）',
    audit_time          DATETIME     DEFAULT NULL COMMENT '审核时间',
    audit_remark        VARCHAR(200) DEFAULT NULL COMMENT '审核意见（驳回必填）',
    cancel_reason       VARCHAR(200) DEFAULT NULL COMMENT '作废原因',
    create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_no (order_no),
    KEY idx_sc_status (status)
) ENGINE = InnoDB COMMENT = '库存盘点单';

-- 盘点明细（DR-14）
CREATE TABLE IF NOT EXISTS stock_check_item (
    id          BIGINT UNSIGNED AUTO_INCREMENT COMMENT '明细ID',
    order_id    BIGINT UNSIGNED NOT NULL COMMENT '盘点单ID',
    goods_id    BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
    book_qty    DECIMAL(10,3) NOT NULL COMMENT '账面数量（快照）',
    actual_qty  DECIMAL(10,3) DEFAULT NULL COMMENT '实盘数量（NULL=未盘，提交时按账面确认）',
    diff_qty    DECIMAL(10,3) DEFAULT NULL COMMENT '差异数量 = 实盘-账面',
    cost_price  DECIMAL(12,4) NOT NULL DEFAULT 0 COMMENT '成本单价（快照）',
    diff_amount DECIMAL(12,2) DEFAULT NULL COMMENT '差异金额',
    diff_reason VARCHAR(100) DEFAULT NULL COMMENT '差异原因（差异≠0时必填，FR-INV-08）',
    remark      VARCHAR(200) DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sci (order_id, goods_id),
    KEY idx_sci_goods (goods_id)
) ENGINE = InnoDB COMMENT = '盘点明细';

-- 报损报溢单（DR-15，FR-INV-10）：单商品单据，店长审核
CREATE TABLE IF NOT EXISTS loss_order (
    id           BIGINT UNSIGNED AUTO_INCREMENT COMMENT '单据ID',
    order_no     VARCHAR(20)  NOT NULL COMMENT '单号：BS+yyyyMMdd+4位流水（报损）/BY+yyyyMMdd+4位流水（报溢）',
    store_id     BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '门店ID（预留）',
    biz_type     VARCHAR(10)  NOT NULL COMMENT '类型：LOSS报损出库/OVERFLOW报溢入库',
    goods_id     BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
    quantity     DECIMAL(10,3) NOT NULL COMMENT '数量 >0（落库为流水时带方向）',
    cost_price   DECIMAL(12,4) NOT NULL COMMENT '成本单价（报溢时录入）',
    amount       DECIMAL(12,2) NOT NULL COMMENT '金额 = 数量×成本单价',
    reason       VARCHAR(30)  NOT NULL COMMENT '原因（字典 loss_reason：过期/破损/失窃/赠品/样品等）',
    apply_name   VARCHAR(30)  NOT NULL COMMENT '申请人',
    status       VARCHAR(10)  NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING待审核/AUDITED已审核/REJECTED已驳回',
    auditor_name VARCHAR(30)  DEFAULT NULL COMMENT '审核人（店长）',
    audit_time   DATETIME     DEFAULT NULL COMMENT '审核时间',
    audit_remark VARCHAR(200) DEFAULT NULL COMMENT '审核意见',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
    remark       VARCHAR(200) DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (id),
    UNIQUE KEY uk_lo_no (order_no),
    KEY idx_lo_goods (goods_id),
    KEY idx_lo_status (status)
) ENGINE = InnoDB COMMENT = '报损/报溢单（单商品，与销售退货分开统计）';

-- 库存日快照（DR-38，FR-INV-12 Could）
CREATE TABLE IF NOT EXISTS inventory_snapshot (
    id          BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
    snap_date   DATE         NOT NULL COMMENT '快照日期',
    goods_id    BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
    quantity    DECIMAL(10,3) NOT NULL COMMENT '结存数量',
    cost_price  DECIMAL(12,4) NOT NULL COMMENT '成本单价',
    cost_amount DECIMAL(12,2) NOT NULL COMMENT '成本金额',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_snap (snap_date, goods_id)
) ENGINE = InnoDB COMMENT = '库存日快照（每日营业结束后生成）';

-- =====================================================================
-- 五、销售（DR-16 ~ DR-21、DR-37）
-- =====================================================================

-- 销售单（DR-16）：仅"已完成"起的正式单据落库（§2.2.2）
CREATE TABLE IF NOT EXISTS sales_order (
    id                BIGINT UNSIGNED AUTO_INCREMENT COMMENT '销售单ID',
    order_no          VARCHAR(22)  NOT NULL COMMENT '销售单号：XS+yyyyMMdd+收银台2位+4位流水（BR-SALE-02）',
    store_id          BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '门店ID（预留）',
    counter_no        TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '收银台号（01~99）',
    cashier_id        BIGINT UNSIGNED NOT NULL COMMENT '收银员ID',
    cashier_name      VARCHAR(30)  NOT NULL COMMENT '收银员姓名',
    shift_id          BIGINT UNSIGNED DEFAULT NULL COMMENT '班次ID',
    member_id         BIGINT UNSIGNED DEFAULT NULL COMMENT '会员ID（散客为空）',
    sale_time         DATETIME     NOT NULL COMMENT '销售时间（结算时间）',
    business_date     DATE         NOT NULL COMMENT '营业日（按结算时间+营业日起始时刻归属，BR-RPT-02）',
    item_count        INT          NOT NULL DEFAULT 0 COMMENT '件数',
    goods_amount      DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '商品总额（折扣前）',
    member_discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '会员优惠额',
    order_discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '店长整单优惠额',
    discount_amount   DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '优惠合计',
    payable_amount    DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '应收金额',
    paid_amount       DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '实收金额',
    change_amount     DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '找零',
    order_discount_rate DECIMAL(5,4) DEFAULT NULL COMMENT '店长整单折扣率（≥最低折扣率参数）',
    cost_amount       DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '成本合计（快照）',
    profit_amount     DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '毛利额',
    return_status     VARCHAR(10)  NOT NULL DEFAULT 'NONE' COMMENT '退货状态：NONE无/PARTIAL部分/ALL全部',
    authorizer_name   VARCHAR(30)  DEFAULT NULL COMMENT '店长授权人（改价/整单折扣时）',
    remark            VARCHAR(200) DEFAULT NULL COMMENT '备注',
    request_token     VARCHAR(64)  DEFAULT NULL COMMENT '请求令牌（提交防重，NFR-REL-02）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_so_no (order_no),
    UNIQUE KEY uk_so_token (request_token),
    KEY idx_so_time (sale_time),
    KEY idx_so_biz_date (business_date),
    KEY idx_so_cashier (cashier_id, sale_time),
    KEY idx_so_member (member_id),
    KEY idx_so_shift (shift_id)
) ENGINE = InnoDB COMMENT = '销售单（结算成功后不可修改删除，BR-SALE-08）';

-- 销售明细（DR-17）：全部为快照字段（BR-SALE-04、DR-C-05）
CREATE TABLE IF NOT EXISTS sales_order_item (
    id            BIGINT UNSIGNED AUTO_INCREMENT COMMENT '明细ID',
    order_id      BIGINT UNSIGNED NOT NULL COMMENT '销售单ID',
    goods_id      BIGINT UNSIGNED DEFAULT NULL COMMENT '商品ID（无码商品为空）',
    goods_name    VARCHAR(100) NOT NULL COMMENT '商品名称快照',
    barcode       VARCHAR(20)  DEFAULT NULL COMMENT '条码快照',
    unit_price    DECIMAL(12,2) NOT NULL COMMENT '成交取价（促销价/会员价/零售价，BR-GOODS-04）',
    quantity      DECIMAL(10,3) NOT NULL COMMENT '数量（普通正整数/称重3位小数）',
    is_weighed    TINYINT      NOT NULL DEFAULT 0 COMMENT '是否称重商品',
    net_weight    DECIMAL(10,3) DEFAULT NULL COMMENT '净重（称重商品）',
    amount        DECIMAL(12,2) NOT NULL COMMENT '行金额（折扣前）= 单价×数量',
    discount_rate DECIMAL(5,4) NOT NULL DEFAULT 1 COMMENT '行最终折扣率（会员×整单）',
    discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '行优惠额（分摊后，尾差入最后一行，BR-SALE-03）',
    paid_amount   DECIMAL(12,2) NOT NULL COMMENT '行实付金额',
    cost_price    DECIMAL(12,4) NOT NULL DEFAULT 0 COMMENT '成本单价快照',
    cost_amount   DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '成本额快照',
    profit_amount DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '毛利额 = 实付-成本额',
    returned_qty  DECIMAL(10,3) NOT NULL DEFAULT 0 COMMENT '已退数量',
    line_type     VARCHAR(10)  NOT NULL DEFAULT 'NORMAL' COMMENT '行类型：NORMAL档案商品/TEMP无码商品（FR-SALE-13）',
    remark        VARCHAR(200) DEFAULT NULL COMMENT '行备注',
    PRIMARY KEY (id),
    KEY idx_soi_order (order_id),
    KEY idx_soi_goods (goods_id),
    KEY idx_soi_barcode (barcode)
) ENGINE = InnoDB COMMENT = '销售明细（快照不可变）';

-- 收款记录（DR-18）：一单多收款方式（FR-SALE-06）
CREATE TABLE IF NOT EXISTS payment_record (
    id            BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
    order_id      BIGINT UNSIGNED NOT NULL COMMENT '销售单ID',
    pay_type      VARCHAR(20)  NOT NULL COMMENT '收款方式（字典 pay_type）：CASH现金/BANK银行卡/MOBILE移动支付/BALANCE会员储值',
    amount        DECIMAL(12,2) NOT NULL COMMENT '收款金额',
    pay_time      DATETIME     NOT NULL COMMENT '收款时间',
    operator_name VARCHAR(30)  NOT NULL COMMENT '操作人（收银员）',
    PRIMARY KEY (id),
    KEY idx_pr_order (order_id),
    KEY idx_pr_type_time (pay_type, pay_time)
) ENGINE = InnoDB COMMENT = '销售收款记录';

-- 销售退货单（DR-19，FR-SALE-10）
CREATE TABLE IF NOT EXISTS return_order (
    id             BIGINT UNSIGNED AUTO_INCREMENT COMMENT '退货单ID',
    order_no       VARCHAR(22)  NOT NULL COMMENT '退货单号：XT+yyyyMMdd+收银台2位+4位流水（BR-SALE-02）',
    store_id       BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '门店ID（预留）',
    sales_order_id BIGINT UNSIGNED NOT NULL COMMENT '原销售单ID',
    member_id      BIGINT UNSIGNED DEFAULT NULL COMMENT '会员ID（原单会员）',
    return_reason  VARCHAR(20)  NOT NULL COMMENT '退货原因（字典 return_reason）',
    reason_remark  VARCHAR(200) DEFAULT NULL COMMENT '原因说明',
    refund_amount  DECIMAL(12,2) NOT NULL COMMENT '退款金额（按原成交价，BR-SALE-06）',
    refund_type    VARCHAR(20)  NOT NULL COMMENT '退款方式：CASH/BANK/MOBILE/BALANCE（退回储值）',
    cashier_name   VARCHAR(30)  NOT NULL COMMENT '退货经办人（收银员）',
    authorizer_name VARCHAR(30) DEFAULT NULL COMMENT '授权人（店长，超阈值或误操作时，FR-SALE-11）',
    authorize_time DATETIME     DEFAULT NULL COMMENT '授权时间',
    return_time    DATETIME     NOT NULL COMMENT '退货时间',
    business_date  DATE         NOT NULL COMMENT '营业日',
    request_token  VARCHAR(64)  DEFAULT NULL COMMENT '请求令牌（提交防重，NFR-REL-02）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_rt_no (order_no),
    UNIQUE KEY uk_rt_token (request_token),
    KEY idx_rt_sales (sales_order_id),
    KEY idx_rt_member (member_id),
    KEY idx_rt_time (return_time)
) ENGINE = InnoDB COMMENT = '销售退货单';

-- 退货明细（DR-20）
CREATE TABLE IF NOT EXISTS return_order_item (
    id             BIGINT UNSIGNED AUTO_INCREMENT COMMENT '明细ID',
    return_id      BIGINT UNSIGNED NOT NULL COMMENT '退货单ID',
    sales_item_id  BIGINT UNSIGNED NOT NULL COMMENT '原销售明细ID',
    goods_id       BIGINT UNSIGNED DEFAULT NULL COMMENT '商品ID',
    return_qty     DECIMAL(10,3) NOT NULL COMMENT '退货数量（≤原数量-已退）',
    refund_price   DECIMAL(12,2) NOT NULL COMMENT '退款单价（原成交单价）',
    refund_amount  DECIMAL(12,2) NOT NULL COMMENT '退款金额',
    cost_price     DECIMAL(12,4) NOT NULL DEFAULT 0 COMMENT '成本单价（原快照回冲）',
    cost_amount    DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '成本回冲额',
    remark         VARCHAR(200) DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (id),
    KEY idx_roi_return (return_id),
    KEY idx_roi_sales_item (sales_item_id)
) ENGINE = InnoDB COMMENT = '销售退货明细';

-- 挂单（DR-21，FR-SALE-08）：临时态，24小时定时清理（IR-SW-06）
CREATE TABLE IF NOT EXISTS hold_order (
    id           BIGINT UNSIGNED AUTO_INCREMENT COMMENT '挂单ID',
    counter_no   TINYINT UNSIGNED NOT NULL COMMENT '收银台号',
    cashier_name VARCHAR(30)  NOT NULL COMMENT '收银员',
    member_id    BIGINT UNSIGNED DEFAULT NULL COMMENT '会员ID（挂单时已选）',
    items_json   TEXT         NOT NULL COMMENT '明细快照JSON',
    item_count   INT          NOT NULL DEFAULT 0 COMMENT '件数',
    amount       DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '金额摘要',
    hold_time    DATETIME     NOT NULL COMMENT '挂单时间',
    status       VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE待取/TAKEN已取/DELETED已删除',
    taken_time   DATETIME     DEFAULT NULL COMMENT '取单时间',
    PRIMARY KEY (id),
    KEY idx_hold_counter (counter_no, status)
) ENGINE = InnoDB COMMENT = '收银挂单（临时态，不扣库存不计报表）';

-- 收银班次（DR-37，FR-SALE-12）
CREATE TABLE IF NOT EXISTS cashier_shift (
    id            BIGINT UNSIGNED AUTO_INCREMENT COMMENT '班次ID',
    cashier_id    BIGINT UNSIGNED NOT NULL COMMENT '收银员ID',
    cashier_name  VARCHAR(30)  NOT NULL COMMENT '收银员姓名',
    counter_no    TINYINT UNSIGNED NOT NULL COMMENT '收银台号',
    start_time    DATETIME     NOT NULL COMMENT '开班时间',
    end_time      DATETIME     DEFAULT NULL COMMENT '交班时间',
    order_count   INT          NOT NULL DEFAULT 0 COMMENT '销售单数',
    sales_amount  DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '销售总额（净销售）',
    cash_amount   DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '现金收款额',
    bank_amount   DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '银行卡收款额',
    mobile_amount DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '移动支付收款额',
    balance_amount DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '储值收款额',
    return_amount DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '退货额',
    expected_cash DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '应收现金 = 现金-现金退款',
    status        VARCHAR(10)  NOT NULL DEFAULT 'WORKING' COMMENT '状态：WORKING在班/CLOSED已交班',
    PRIMARY KEY (id),
    KEY idx_shift_cashier (cashier_id, start_time),
    KEY idx_shift_status (status)
) ENGINE = InnoDB COMMENT = '收银班次（开班/交班对账）';

-- =====================================================================
-- 六、会员（DR-22 ~ DR-25）
-- =====================================================================

-- 会员表（DR-22）
CREATE TABLE IF NOT EXISTS member (
    id               BIGINT UNSIGNED AUTO_INCREMENT COMMENT '会员ID',
    member_no        VARCHAR(10)  NOT NULL COMMENT '会员编号：M+8位流水（BR-MEM-02）',
    name             VARCHAR(30)  NOT NULL COMMENT '姓名',
    phone            VARCHAR(11)  NOT NULL COMMENT '手机号（全局唯一，主要识别键，BR-MEM-01）',
    gender           TINYINT      DEFAULT NULL COMMENT '性别：1男 2女（不采集证件信息，C-07）',
    birthday         DATE         DEFAULT NULL COMMENT '生日',
    address          VARCHAR(200) DEFAULT NULL COMMENT '地址',
    level_id         BIGINT UNSIGNED NOT NULL COMMENT '会员等级ID',
    points           INT          NOT NULL DEFAULT 0 COMMENT '当前积分',
    balance          DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '储值余额（不可为负，BR-MEM-07）',
    total_consume    DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '累计消费额 = Σ实付-Σ退款（等级判定依据，BR-MEM-06）',
    consume_times    INT          NOT NULL DEFAULT 0 COMMENT '消费次数',
    last_consume_time DATETIME    DEFAULT NULL COMMENT '最近消费时间',
    register_name    VARCHAR(30)  NOT NULL COMMENT '注册人（收银员）',
    register_time    DATETIME     NOT NULL COMMENT '注册时间',
    status           TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1正常 0停用（BR-MEM-03）',
    remark           VARCHAR(200) DEFAULT NULL COMMENT '备注',
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_member_no (member_no),
    UNIQUE KEY uk_member_phone (phone),
    KEY idx_member_level (level_id)
) ENGINE = InnoDB COMMENT = '会员档案';

-- 会员等级（DR-23，FR-MEM-05）
CREATE TABLE IF NOT EXISTS member_level (
    id           BIGINT UNSIGNED AUTO_INCREMENT COMMENT '等级ID',
    name         VARCHAR(30)  NOT NULL COMMENT '等级名称',
    threshold    DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '升级门槛（累计消费额）',
    discount_rate DECIMAL(5,4) NOT NULL DEFAULT 1 COMMENT '折扣率（如0.98=98折）',
    point_rate   DECIMAL(5,4) NOT NULL DEFAULT 1 COMMENT '积分倍率',
    sort_no      INT          NOT NULL DEFAULT 0 COMMENT '排序（门槛升序）',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1启用 0停用',
    create_by    VARCHAR(30)  DEFAULT NULL COMMENT '创建人',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by    VARCHAR(30)  DEFAULT NULL COMMENT '更新人',
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_level_name (name)
) ENGINE = InnoDB COMMENT = '会员等级规则';

-- 积分流水（DR-24，FR-MEM-07）：不可变
CREATE TABLE IF NOT EXISTS point_record (
    id            BIGINT UNSIGNED AUTO_INCREMENT COMMENT '流水ID',
    member_id     BIGINT UNSIGNED NOT NULL COMMENT '会员ID',
    change_type   VARCHAR(10)  NOT NULL COMMENT '变动类型：CONSUME消费/RETURN退货/MANUAL人工调整（FR-MEM-09）',
    change_value  INT          NOT NULL COMMENT '变动值（消费+，退货-）',
    after_value   INT          NOT NULL COMMENT '变动后积分余额',
    biz_no        VARCHAR(22)  DEFAULT NULL COMMENT '来源单据号（销售单/退货单）',
    operator_name VARCHAR(30)  NOT NULL COMMENT '操作人',
    create_time   DATETIME     NOT NULL COMMENT '时间',
    remark        VARCHAR(200) DEFAULT NULL COMMENT '备注（人工调整原因）',
    PRIMARY KEY (id),
    KEY idx_pt_member (member_id, create_time),
    KEY idx_pt_biz (biz_no)
) ENGINE = InnoDB COMMENT = '积分流水（只增不改不删）';

-- 储值流水（DR-25，FR-MEM-08）：不可变
CREATE TABLE IF NOT EXISTS balance_record (
    id             BIGINT UNSIGNED AUTO_INCREMENT COMMENT '流水ID',
    member_id      BIGINT UNSIGNED NOT NULL COMMENT '会员ID',
    change_type    VARCHAR(10)  NOT NULL COMMENT '变动类型：RECHARGE充值/CONSUME消费/REFUND退款回充/MANUAL人工调整',
    change_amount  DECIMAL(12,2) NOT NULL COMMENT '变动金额（充值+，消费-）',
    after_balance  DECIMAL(12,2) NOT NULL COMMENT '变动后余额（不可为负）',
    pay_type       VARCHAR(20)  DEFAULT NULL COMMENT '收款方式（充值时）',
    biz_no         VARCHAR(22)  DEFAULT NULL COMMENT '来源单据号',
    operator_name  VARCHAR(30)  NOT NULL COMMENT '操作人',
    authorizer_name VARCHAR(30) DEFAULT NULL COMMENT '授权人（充值需店长授权）',
    create_time    DATETIME     NOT NULL COMMENT '时间',
    remark         VARCHAR(200) DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (id),
    KEY idx_br_member (member_id, create_time),
    KEY idx_br_biz (biz_no)
) ENGINE = InnoDB COMMENT = '储值流水（只增不改不删）';
