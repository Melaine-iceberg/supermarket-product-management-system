#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SMMS 仿真数据生成器（AC-D07 / NFR-PERF-05）

生成规模（可通过命令行调整）：
  * 商品 20,000（含分类/单位/品牌数据、库存结存与 INIT 流水、促销数据）
  * 销售单 2,000,000（跨约 36 个月，含明细/收款/库存流水/会员积分与储值流水/退货单）
  * 辅助数据：供应商 50 + 供货关系、采购单 3000（含入库/退货）、报损报溢 3000、会员 5000

数据一致性保证（与后端业务规则一致）：
  * 库存流水 before/after 结存按时间顺序推演，最终结存 = Σ流水（AC-04 可复核，库存体检无差异）
  * 销售单金额 = Σ明细：优惠按"行分摊、尾差入最后一行"（BR-SALE-03），金额全程整数分运算、
    四舍五入（HALF_UP），与后端 BigDecimal 结果一致
  * 成本快照 = 商品固定成本单价（等价于"每次入库单价恒定"的移动加权平均，BR-INV-06）
  * 会员积分 = floor(积分基数 × 每元积分数 × 等级倍率)，储值支付不超余额（BR-MEM-05/07）
  * 退货按原单实付单价比例退款、成本按快照回冲（BR-SALE-06），库存永不出现负值

用法：
  python3 tools/gen-perf-data.py [--goods 20000] [--orders 2000000] ... | mysql -h127.0.0.1 -usmms -p*** smms
  或使用包装脚本：tools/gen-perf-data.sh（见文件内注释）

注意：
  * 请在已完成 db/schema.sql + db/init-data.sql 初始化的库上执行；重复执行会因唯一键冲突报错，
    如需重置业务数据请加 --truncate（会清空商品/单据/会员等业务表，不影响系统账号与字典）。
  * 输出为 SQL 文本流（标准输出），进度信息在标准错误。
"""

import argparse
import bisect
import heapq
import random
import sys
import time
from datetime import date, datetime, timedelta

# =====================================================================
# 常量与词表
# =====================================================================

DICT_PAY_TYPES = ["CASH", "BANK", "MOBILE", "BALANCE"]
PAY_WEIGHTS = [0.38, 0.14, 0.38, 0.10]
DICT_RETURN_REASONS = ["QUALITY", "WRONG_ITEM", "MISTAKE", "OTHER"]
RETURN_REASON_WEIGHTS = [0.55, 0.20, 0.10, 0.15]
LOSS_REASONS = ["EXPIRED", "DAMAGED", "STOLEN", "GIFT", "SAMPLE", "OTHER"]
LOSS_REASON_WEIGHTS = [0.45, 0.25, 0.10, 0.08, 0.07, 0.05]

# 品牌/商品/规格词表：(词, 拼音首字母)——名称与拼音首字母保持一致，供收银检索（FR-SALE-02）
BRANDS_PACK = [
    ("康师傅", "ksf"), ("统一", "ty"), ("农夫山泉", "nfsq"), ("娃哈哈", "whh"), ("可口可乐", "kkkl"),
    ("百事", "bs"), ("王老吉", "wlj"), ("加多宝", "jdb"), ("伊利", "yl"), ("蒙牛", "mn"),
    ("光明", "gm"), ("旺旺", "ww"), ("乐事", "ls"), ("洽洽", "qq"), ("奥利奥", "alo"),
    ("达利园", "dly"), ("盼盼", "pp"), ("徐福记", "xfj"), ("好丽友", "hly"), ("海天", "ht"),
    ("李锦记", "ljj"), ("老干妈", "lgm"), ("太太乐", "ttl"), ("金龙鱼", "jly"), ("福临门", "flm"),
    ("三只松鼠", "szss"), ("良品铺子", "lppz"), ("卫龙", "wl"), ("双汇", "sh"), ("金锣", "jl"),
    ("立白", "lb"), ("蓝月亮", "lyl"), ("雕牌", "dp"), ("维达", "wd"), ("清风", "qf"),
    ("心相印", "xxy"), ("洁柔", "jr"), ("佳洁士", "jjs"), ("高露洁", "glj"), ("六神", "ls"),
]
PRODUCTS_PACK = [
    ("矿泉水", "kqs"), ("纯净水", "cjs"), ("苏打水", "sds"), ("可乐", "kl"), ("雪碧", "xb"),
    ("冰红茶", "bhc"), ("绿茶", "lc"), ("橙汁", "cz"), ("椰汁", "yz"), ("牛奶", "nn"),
    ("酸奶", "sn"), ("儿童奶", "etn"), ("饼干", "bg"), ("薯片", "sp"), ("虾条", "xt"),
    ("巧克力", "qlk"), ("糖果", "tg"), ("果冻", "gd"), ("方便面", "fbm"), ("挂面", "gm"),
    ("粉丝", "fs"), ("大米", "dm"), ("面粉", "mf"), ("食用油", "syy"), ("酱油", "jy"),
    ("食醋", "sc"), ("蚝油", "hy"), ("鸡精", "jj"), ("火锅底料", "hgdl"), ("瓜子", "gz"),
    ("花生", "hs"), ("开心果", "kxg"), ("牛肉干", "nrg"), ("面包", "mb"), ("蛋糕", "dg"),
    ("火腿肠", "hzt"), ("午餐肉", "wcr"), ("洗发水", "xfs"), ("沐浴露", "myl"), ("牙膏", "yg"),
    ("牙刷", "ys"), ("洗衣液", "xyy"), ("洗洁精", "xjj"), ("消毒液", "xdy"), ("抽纸", "cz"),
    ("卷纸", "jz"), ("湿巾", "sj"), ("垃圾袋", "ljd"), ("保鲜膜", "bxm"), ("拖把", "tb"),
    ("毛巾", "mj"), ("水杯", "sb"),
]
SPECS_PACK = [
    ("", ""), ("家庭装", "jtz"), ("大瓶装", "dpz"), ("小包装", "xbz"), ("整箱装", "zxz"),
    ("精装", "jz"), ("实惠装", "shz"), ("礼盒装", "lhz"), ("经典款", "jdk"), ("新包装", "xbz"),
]
BRANDS_FRESH = [("自营", "zy"), ("农家", "nj"), ("绿色", "ls"), ("有机", "yj"), ("精选", "jx"), ("本地", "bd")]
PRODUCTS_FRESH = [
    ("苹果", "pg"), ("香蕉", "xj"), ("橙子", "cz"), ("西红柿", "xhs"), ("黄瓜", "hg"),
    ("土豆", "td"), ("青菜", "qc"), ("菠菜", "bc"), ("白菜", "bc"), ("芹菜", "qc"),
    ("猪肉", "zr"), ("牛肉", "nr"), ("鸡腿", "jt"), ("鸡蛋", "jd"), ("草鱼", "cy"), ("基围虾", "jwx"),
]
SPECS_FRESH = [("散装", "sz"), ("优选", "yx"), ("精品", "jp"), ("特级", "tj"), ("普通", "pt")]

SURNAMES = "赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜"
GIVEN_NAMES = ["秀英", "伟", "芳", "娜", "敏", "静", "丽", "强", "磊", "军", "洋", "勇", "艳",
               "杰", "娟", "涛", "明", "超", "秀兰", "霞", "平", "刚", "桂英", "建国", "文",
               "玲", "华", "金凤", "鹏", "雪梅"]
SUPPLIER_WORDS = ["商贸", "食品", "供应链", "实业", "配送", "贸易", "粮油", "日化"]
SUPPLIER_REGIONS = ["临江", "北岭", "南湖", "东港", "西塘", "青云", "长丰", "安泰", "广源", "宏泰"]

CASHIER_NAMES = ["周雅静", "吴鹏", "孙晓梅", "郑浩然", "冯丽娜"]
MANAGER_NAME = "王店长"

# 单行输出各表缓冲到该行数后生成一条多值 INSERT
BATCH_ROWS = 800
COMMIT_EVERY_ROWS = 40000


def half_up(a, b):
    """整数 + 0.5 后向下取整的 HALF_UP 除法（a>=0, b>0），对齐 BigDecimal.HALF_UP"""
    return (2 * a + b) // (2 * b)


def fmt_cents(c):
    """分 → 元字符串（2 位小数）"""
    sign = "-" if c < 0 else ""
    c = abs(c)
    return "%s%d.%02d" % (sign, c // 100, c % 100)


def fmt_cost4(c):
    """分 → 成本单价字符串（4 位小数，DECIMAL(12,4) 字面量，如 1234 分 → 12.3400）"""
    sign = "-" if c < 0 else ""
    c = abs(c)
    return "%s%d.%02d00" % (sign, c // 100, c % 100)


def fmt_qty(milli, weighed):
    """千分之一单位数量 → SQL 字面量：称重商品保留 3 位小数，普通商品输出整数（支持负数）"""
    sign = "-" if milli < 0 else ""
    m = abs(milli)
    if weighed:
        return "%s%d.%03d" % (sign, m // 1000, m % 1000)
    return "%s%d" % (sign, m // 1000)


def fmt_rate(bp):
    """万分比 → decimal(5,4) 字面量，如 9800 → 0.9800"""
    return "%d.%04d" % (bp // 10000, bp % 10000)


def dt_str(dt):
    return "%04d-%02d-%02d %02d:%02d:%02d" % (dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second)


def d_str(d):
    return "%04d-%02d-%02d" % (d.year, d.month, d.day)


def sql_str(s):
    if s is None:
        return "NULL"
    return "'" + s.replace("\\", "\\\\").replace("'", "''") + "'"


class Writer:
    """按表聚合的多值 INSERT 输出器：自动分批、定期 COMMIT，控制单次事务体积"""

    def __init__(self, out, batch=BATCH_ROWS, commit_every=COMMIT_EVERY_ROWS):
        self.out = out
        self.batch = batch
        self.commit_every = commit_every
        self.buf = {}
        self.cols = {}
        self.rows_written = 0
        self.rows_since_commit = 0
        self.stat = {}

    def insert(self, table, cols, values):
        if table not in self.buf:
            self.buf[table] = []
            self.cols[table] = cols
        buf = self.buf[table]
        buf.append(values)
        self.stat[table] = self.stat.get(table, 0) + 1
        if len(buf) >= self.batch:
            self._flush_table(table)

    def _flush_table(self, table):
        buf = self.buf[table]
        if not buf:
            return
        n = len(buf)
        sql = "INSERT INTO %s (%s) VALUES\n%s;\n" % (table, self.cols[table], ",\n".join(buf))
        self.out.write(sql)
        buf.clear()
        self.rows_written += n
        self.rows_since_commit += n
        if self.rows_since_commit >= self.commit_every:
            self._flush_all_tables()
            self.out.write("COMMIT;\n")
            self.rows_since_commit = 0

    def _flush_all_tables(self):
        for t in list(self.buf.keys()):
            self._flush_table(t)

    def raw(self, sql):
        self._flush_all_tables()
        self.out.write(sql)

    def close(self):
        self._flush_all_tables()
        self.out.write("COMMIT;\n")
        self.out.flush()


# =====================================================================
# 生成器主体
# =====================================================================

class PerfDataGenerator:

    def __init__(self, args):
        self.args = args
        self.rng = random.Random(args.seed)
        self.w = Writer(sys.stdout, batch=args.batch)
        self.t0 = time.time()

        # 商品运行时档案：gid -> [name, barcode, weighed, retail_c, cost_c, member_c(-1=无), allow_return, plu]
        self.goods = {}
        self.goods_ids = []
        # 库存运行时状态：gid -> 结存（千分之一单位）
        self.qty_milli = {}
        # 会员运行时状态：mid -> [points, balance_c]
        self.member_state = {}
        self.members = []  # [(mid, rate_bp, point_bp, name)]

    # ---------------- 通用 ----------------

    def log(self, msg):
        sys.stderr.write("[gen-perf-data] %s\n" % msg)
        sys.stderr.flush()

    def scaled_table(self, table, seq_start=1):
        """按 --scale 缩放的数量（便于小规模联调）"""
        return max(1, int(round(table * self.args.scale)))

    # ---------------- 阶段 1：分类、供应商、用户 ----------------

    def emit_truncate(self):
        self.log("清空既有业务数据（--truncate）...")
        # 仅清理脚本自身生成的扩展账号（id 100+），避免破坏初始化演示账号
        self.w.raw("DELETE FROM sys_user_role WHERE user_id BETWEEN 100 AND 999;\n"
                   "DELETE FROM sys_user WHERE id BETWEEN 100 AND 999;\n"
                   # 分类仅清理本脚本的 1000+ 段，保留初始化的一级分类（1~4）
                   "DELETE FROM category WHERE id >= 1000;\n")
        for t in ["hold_order", "cashier_shift", "payment_record", "return_order_item", "return_order",
                  "sales_order_item", "sales_order", "inventory_record", "inventory_snapshot",
                  "loss_order", "stock_check_item", "stock_check_order",
                  "purchase_return_item", "purchase_return_order",
                  "receipt_order_item", "receipt_order", "purchase_order_item", "purchase_order",
                  "supplier_goods", "supplier", "promotion", "price_history",
                  "inventory", "goods", "brand",
                  "balance_record", "point_record", "member"]:
            self.w.raw("DELETE FROM %s;\n" % t)

    def emit_categories(self):
        """在初始化的一级分类（1 食品/2 饮料/3 日用品/4 生鲜果蔬）下补二级、三级分类（本脚本使用 1000+/2000+ 段 id）"""
        mid_names = ["粮油副食", "休闲零食", "方便速食", "调味干货"]
        leaf_names = ["米面", "食用油", "调味", "干货", "膨化", "糕点", "糖果", "坚果",
                      "方便面", "速食汤", "自热食品", "罐头", "酱油醋", "香辛料", "火锅料", "酱菜"]
        self.log("生成分类（二级 %d / 三级 %d）..." % (4 * len(mid_names), 4 * len(mid_names) * 4))
        cols = "id, parent_id, name, level, path, sort_no, status, create_by"
        tops = ["食品", "饮料", "日用品", "生鲜果蔬"]
        self.leaf_ids = []
        n = 0
        for top in (1, 2, 3, 4):
            for i, mn in enumerate(mid_names, start=1):
                mid = 1000 + (top - 1) * 16 + i
                self.w.insert("category", cols, "(%d, %d, %s, 2, %s, %d, 1, 'perf-gen')" % (
                    mid, top, sql_str("%s·%s" % (tops[top - 1], mn)),
                    sql_str("%d/%d" % (top, mid)), i))
            # 每个二级分类挂 4 个三级分类（4 大类 × 4 二级 × 4 三级 = 64 个叶子）
            for j in range(4):
                mid = 1000 + (top - 1) * 16 + j + 1
                for _ in range(4):
                    leaf = 2000 + n
                    lname = leaf_names[n % len(leaf_names)] + (str(n // len(leaf_names) + 1) if n >= len(leaf_names) else "")
                    n += 1
                    self.w.insert("category", cols, "(%d, %d, %s, 3, %s, %d, 1, 'perf-gen')" % (
                        leaf, mid, sql_str(lname), sql_str("%d/%d/%d" % (top, mid, leaf)), n % 4 + 1))
                    self.leaf_ids.append(leaf)

    def emit_brands(self):
        """品牌档案（first_letter 为拼音首字母大写首字母），商品档案引用"""
        self.log("生成品牌 %d 个..." % (len(BRANDS_PACK) + len(BRANDS_FRESH)))
        cols = "id, name, first_letter, status, create_by"
        self.brand_ids = {}
        bid = 1000
        for name, initial in BRANDS_PACK + BRANDS_FRESH:
            bid += 1
            self.brand_ids[name] = bid
            self.w.insert("brand", cols, "(%d, %s, %s, 1, 'perf-gen')" % (
                bid, sql_str(name), sql_str(initial[:1].upper())))

    def emit_users(self):
        """扩展收银账号（沿用演示账号 cashier 的 BCrypt 哈希，密码同为 Cashier@2026），供业绩/交班演示"""
        self.log("生成扩展收银员账号 5 个...")
        cashier_hash = "$2b$10$tlaxcRV6BK5oUDN.SxOZeurRHGdPUWJXat3bgzjswAIKDXUsKV3Au"
        cols = "id, username, password, real_name, phone, status, remark, create_by"
        for i, name in enumerate(CASHIER_NAMES, start=1):
            uid = 100 + i
            self.w.insert("sys_user", cols, "(%d, %s, %s, %s, %s, 1, '仿真数据收银员', 'perf-gen')" % (
                uid, sql_str("cashier%d" % (i + 1)), sql_str(cashier_hash), sql_str(name),
                sql_str("1381000%04d" % i)))
            self.w.insert("sys_user_role", "user_id, role_id", "(%d, 5)" % uid)
        self.cashiers = [(5, "陈收银")] + [(100 + i, n) for i, n in enumerate(CASHIER_NAMES, start=1)]

    def emit_suppliers(self):
        self.log("生成供应商 %d 家..." % self.args.suppliers)
        cols = ("id, supplier_code, name, contact_person, phone, address, email, bank_name, bank_account,"
                " settle_type, credit_days, lead_time_days, total_purchase_amount, status, create_by")
        self.suppliers = []
        rng = self.rng
        used = set()
        for i in range(1, self.args.suppliers + 1):
            while True:
                name = "%s%s%s有限公司" % (rng.choice(SUPPLIER_REGIONS), rng.choice(SUPPLIER_WORDS),
                                          "第%d分公司" % i if rng.random() < 0.3 else "")
                if name not in used:
                    used.add(name)
                    break
            settle, credit = rng.choice([("CASH", None), ("MONTHLY", 30), ("CREDIT", rng.choice([45, 60, 90]))])
            self.suppliers.append((i, name))
            self.w.insert("supplier", cols, "(%d, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %d, 0, 1, 'perf-gen')" % (
                i, sql_str("S%05d" % i), sql_str(name), sql_str(rng.choice(["刘经理", "陈主管", "赵业务", "孙经理"])),
                sql_str("139%08d" % rng.randint(0, 99999999)),
                sql_str("%s市%s路%d号" % (rng.choice(SUPPLIER_REGIONS), rng.choice(["建设", "人民", "解放", "工业"]), rng.randint(1, 999))),
                sql_str("supplier%d@example.com" % i), sql_str("中国银行%s支行" % rng.choice(SUPPLIER_REGIONS)),
                sql_str("62%d" % rng.randint(10**14, 10**15 - 1)),
                sql_str(settle), "NULL" if credit is None else str(credit), rng.choice([2, 3, 5, 7]),
            ))

    # ---------------- 阶段 2：商品、库存、促销 ----------------

    def _gen_product(self, i):
        """生成第 i 个商品的展示与价格属性（返回 dict 片段）"""
        rng = self.rng
        fresh = rng.random() < self.args.fresh_ratio
        if fresh:
            brand, bi = rng.choice(BRANDS_FRESH)
            product, pi = rng.choice(PRODUCTS_FRESH)
            spec, si = rng.choice(SPECS_FRESH)
            unit_id, weighed = rng.choice([(5, 1), (6, 1)]), 1
        else:
            brand, bi = rng.choice(BRANDS_PACK)
            product, pi = rng.choice(PRODUCTS_PACK)
            spec, si = rng.choice(SPECS_PACK)
            unit_id, weighed = (rng.choice([1, 2, 3, 4, 7, 8, 9, 10]), 0)
        if isinstance(unit_id, tuple):
            unit_id = unit_id[0]
        name = "%s%s%s" % (brand, product, spec)
        # 价格：低价快消 / 中价 / 高价三档（单位：分）
        tier = rng.random()
        if fresh:
            retail_c = rng.randint(150, 5200)          # 生鲜按 kg 计价
        elif tier < 0.45:
            retail_c = rng.randint(150, 1500)
        elif tier < 0.85:
            retail_c = rng.randint(1500, 6000)
        else:
            retail_c = rng.randint(6000, 30000)
        if retail_c % 10 in (3, 6):
            retail_c += 1
        cost_c = max(10, int(retail_c * rng.uniform(0.62, 0.88)))
        member_c = int(retail_c * rng.uniform(0.94, 0.985)) if rng.random() < 0.55 else -1
        allow_return = 0 if (fresh and rng.random() < 0.12) else 1
        lo = rng.choice([10, 20, 30, 50])
        hi = lo * rng.choice([3, 4, 5, 8])
        return {
            "name": name, "brand": brand, "initial": bi + pi + si, "weighed": weighed, "unit_id": unit_id,
            "retail_c": retail_c, "cost_c": cost_c, "member_c": member_c,
            "allow_return": allow_return, "safety_lo": lo, "safety_hi": hi,
        }

    @staticmethod
    def _ean13(seq):
        """由序号生成合法 EAN-13 条码（前缀 690，含校验位）"""
        body = "690%09d" % seq
        s = 0
        for pos, ch in enumerate(body):
            d = ord(ch) - 48
            s += d if pos % 2 == 0 else 3 * d
        return body + str((10 - s % 10) % 10)

    def emit_goods(self):
        n = self.args.goods
        self.log("生成商品 %d（含库存/促销，库存结存与 INIT 流水在回写阶段统一落库）..." % n)
        gcols = ("id, goods_code, barcode, name, pinyin_initial, category_id, brand_id, unit_id, spec, origin,"
         " purchase_price, retail_price, member_price, plu_code, is_weighed, allow_return,"
         " safety_stock_lower, safety_stock_upper, status, create_by")
        rng = self.rng
        origins = ["临江市", "北岭市", "南湖市", "东港市", "进口"]
        # 权重：偏态分布（Zipf 0.85），头部商品销量占比高，贴近真实商超动销结构
        rank = list(range(n))
        rng.shuffle(rank)
        weights = [1.0 / ((r + 6) ** 0.85) for r in rank]
        total_w = sum(weights)
        cum = []
        acc = 0.0
        for wv in weights:
            acc += wv
            cum.append(acc)
        self.cum_weights = cum
        self.total_w = total_w
        # 期望销量（用于推算初始库存，保证销售全程不出现负库存）
        total_units = self.args.orders * 2.2 * 2.0 * 1000  # 订单数 × 平均行数 × 平均数量（千分位）
        init_rows = []
        rec_rows = []
        # 与 apply_ledger 保持完全一致的列清单（同一张表的所有 INSERT 必须同构）
        rec_cols = ("id, goods_id, store_id, record_type, change_qty, before_qty, after_qty, unit_cost,"
                    " biz_type, biz_no, operator_name, operate_time, remark")
        t0 = datetime(self.period_start.year, self.period_start.month, self.period_start.day, 8, 0, 0) - timedelta(days=1)
        t0s = dt_str(t0)
        for i in range(1, n + 1):
            gid = 10000 + i
            p = self._gen_product(i)
            leaf = rng.choice(self.leaf_ids)
            weighed = p["weighed"]
            code = "%04d%06d" % (leaf, i)
            barcode = self._ean13(i)
            plu = ("%05d" % (80000 + i)) if weighed else None
            spec = rng.choice(["500ml", "1L", "250g", "400g", "1kg", "5kg", "10卷", "3层", "大包", "小包"]) if not weighed else None
            self.goods[gid] = (p["name"], barcode, weighed, p["retail_c"], p["cost_c"], p["member_c"], p["allow_return"], plu)
            self.goods_ids.append(gid)
            member_price = "NULL" if p["member_c"] < 0 else fmt_cents(p["member_c"])
            init_rows.append("(%d, %s, %s, %s, %s, %d, %d, %d, %s, %s, %s, %s, %s, %s, %d, %d, %s, %d, 1, 'perf-gen')" % (
                gid, sql_str(code), sql_str(barcode), sql_str(p["name"]), sql_str(p["initial"]), leaf,
                self.brand_ids[p["brand"]], p["unit_id"], sql_str(spec), sql_str(rng.choice(origins)),
                fmt_cents(p["cost_c"]), fmt_cents(p["retail_c"]), member_price,
                "NULL" if plu is None else sql_str(plu),
                weighed, p["allow_return"], "%d.000" % p["safety_lo"], p["safety_hi"]))
            # 初始库存 = 期望销量 × (1.15~1.45) + 底量；INIT 流水与结存同步
            # 非称重商品须取整为整件（毫单位 ×1000）：否则毫单位隐藏小数碎片会与
            # 落库的整件数量截断显示不一致，导致 成本金额 ≠ 数量×成本单价
            expect_milli = total_units * weights[i - 1] / total_w
            init_milli = int(expect_milli * rng.uniform(1.15, 1.45)) + rng.randint(20, 60) * 1000
            if not weighed:
                init_milli = init_milli // 1000 * 1000
            self.qty_milli[gid] = init_milli
            # INIT 流水 id 取 1..n 段（销售/采购等流水使用 10^9+ 独立段）
            rec_rows.append("(%d, %d, 1, 'INIT', %s, 0, %s, %s, 'PD', NULL, 'system', %s, '仿真数据期初建账')" % (
                i, gid, fmt_qty(init_milli, weighed), fmt_qty(init_milli, weighed),
                fmt_cost4(p["cost_c"]), sql_str(t0s)))
        for r in init_rows:
            self.w.insert("goods", gcols, r)
        for r in rec_rows:
            self.w.insert("inventory_record", rec_cols, r)
        self.log("商品/库存生成完毕（%.1fs）" % (time.time() - self.t0))
        self.emit_promotions()

    def emit_promotions(self):
        """随机促销：约 5% 商品在历史时段有促销（大部分已过期，少量当前生效，供取价与界面演示）"""
        count = max(1, int(self.args.goods * 0.05 * self.args.scale))
        self.log("生成促销 %d 条..." % count)
        cols = "goods_id, promo_price, start_time, end_time, status, create_by, remark"
        rng = self.rng
        now = datetime.now()
        self.promotions = {}
        for _ in range(count):
            gid = rng.choice(self.goods_ids)
            g = self.goods[gid]
            d0 = self.period_start + timedelta(days=rng.randint(0, max(0, self.total_days - 1)))
            start = datetime(d0.year, d0.month, d0.day, rng.randint(8, 20))
            end = start + timedelta(days=rng.randint(3, 21))
            promo_c = max(10, int(g[3] * rng.uniform(0.55, 0.92)))
            if promo_c >= g[3]:
                promo_c = g[3] - 1
            status = "ACTIVE" if start <= now <= end else ("EXPIRED" if end < now else "ACTIVE")
            self.promotions.setdefault(gid, []).append((start, end, promo_c))
            self.w.insert("promotion", cols, "(%d, %s, %s, %s, %s, 'perf-gen', '仿真促销')" % (
                gid, fmt_cents(promo_c), sql_str(dt_str(start)), sql_str(dt_str(end)), sql_str(status)))

    def price_at(self, gid, t, has_member):
        """取价（BR-GOODS-04）：生效促销价 > 会员价（仅会员单）> 零售价"""
        g = self.goods[gid]
        promo = self.promotions.get(gid)
        if promo:
            for s, e, pc in promo:
                if s <= t <= e:
                    return pc
        if has_member and g[5] >= 0:
            return g[5]
        return g[3]

    # ---------------- 阶段 3：会员 ----------------

    def emit_members(self):
        n = self.args.members
        self.log("生成会员 %d（含充值流水）..." % n)
        cols = ("id, member_no, name, phone, gender, birthday, address, level_id, points, balance,"
                " total_consume, consume_times, register_name, register_time, status")
        rng = self.rng
        rate_by_level = {1: 10000, 2: 9800, 3: 9500}
        point_by_level = {1: 10000, 2: 10000, 3: 12000}
        recharge_events = []
        for i in range(1, n + 1):
            mid = 5000 + i
            name = rng.choice(SURNAMES) + rng.choice(GIVEN_NAMES)
            phone = "13%09d" % (100000000 + i)  # 全局唯一
            level = 1 if rng.random() < 0.72 else (2 if rng.random() < 0.8 else 3)
            status = 0 if rng.random() < 0.02 else 1
            d0 = self.period_start + timedelta(days=rng.randint(0, max(1, self.total_days // 2)))
            reg = datetime(d0.year, d0.month, d0.day, rng.randint(8, 21), rng.randint(0, 59))
            self.members.append((mid, rate_by_level[level], point_by_level[level], name, status))
            self.member_state[mid] = [0, 0]
            self.w.insert("member", cols, "(%d, %s, %s, %s, %d, %s, %s, %d, 0, 0, 0, 0, %s, %s, %d)" % (
                mid, sql_str("M%08d" % i), sql_str(name), sql_str(phone), rng.choice([1, 2]),
                sql_str("%04d-%02d-%02d" % (rng.randint(1960, 2004), rng.randint(1, 12), rng.randint(1, 28))),
                sql_str("临江市某小区%d栋" % rng.randint(1, 30)), level,
                sql_str("陈收银"), sql_str(dt_str(reg)), status))
            # 30% 会员在注册后 1~20 天内充值 1~2 次
            if status == 1 and rng.random() < 0.30:
                for _ in range(rng.randint(1, 2)):
                    t = reg + timedelta(days=rng.randint(1, 20), hours=rng.randint(0, 10))
                    if t.date() > self.period_end:
                        break
                    amt = rng.choice([10000, 20000, 30000, 50000, 100000])
                    recharge_events.append((t, mid, amt, rng.choice(["CASH", "MOBILE", "BANK"])))
        # 充值流水按时间排序后并入事件流
        recharge_events.sort(key=lambda e: e[0])
        self.recharge_events = recharge_events
        self.log("会员生成完毕（%.1fs）" % (time.time() - self.t0))

    # ---------------- 阶段 4：采购 / 报损报溢事件 ----------------

    def _ops_by_time(self, rng, day_from, day_to, hour_lo=8, hour_hi=18):
        d = day_from + timedelta(days=rng.randint(0, max(0, (day_to - day_from).days)))
        return datetime(d.year, d.month, d.day, rng.randint(hour_lo, hour_hi), rng.randint(0, 59), rng.randint(0, 59))

    def gen_purchase_events(self):
        """生成采购单/入库单/采购退货及其库存流水事件（按时间排序，供主循环按序落账）"""
        po_n = self.scaled_table(self.args.purchase_orders)
        self.log("生成采购单 %d（含入库/退货与库存流水）..." % po_n)
        rng = self.rng
        events = []
        day_from, day_to = self.period_start, self.period_end
        # 供应商-商品供货关系：每商品 1~2 家（写 supplier_goods，同时用于采购选品）
        supply_rows = []
        for gi, gid in enumerate(self.goods_ids):
            for sid in rng.sample([s[0] for s in self.suppliers], 1 if rng.random() < 0.6 else 2):
                supply_rows.append((gid, sid))
        for gid, sid in supply_rows:
            g = self.goods[gid]
            self.w.insert("supplier_goods",
                          "supplier_id, goods_id, supply_price, is_default, create_by",
                          "(%d, %d, %s, 1, 'perf-gen')" % (sid, gid, fmt_cents(g[4])))
        # 采购单
        po_cols = ("id, order_no, supplier_id, status, create_by, create_time, submit_time, audit_by, audit_time,"
                   " expect_date, total_amount, received_amount, return_amount, settle_status, settle_amount,"
                   " settle_method, settle_date, settle_by, version, remark")
        poi_cols = "id, order_id, goods_id, quantity, price, amount, received_qty, returned_qty"
        ro_cols = ("id, order_no, purchase_order_id, supplier_id, receipt_time, operator_name, authorizer_name,"
                   " delivery_order_no, total_amount, remark")
        roi_cols = "id, receipt_id, purchase_item_id, goods_id, received_qty, received_price, amount"
        pt_cols = ("id, order_no, purchase_order_id, supplier_id, status, return_reason, total_amount,"
                   " create_by, create_time, audit_by, audit_time, remark")
        pt_item_cols = "id, return_id, purchase_item_id, goods_id, return_qty, return_price, amount"
        po_seq = {}
        rec_seq = {}
        pt_seq = {}
        roi_id = 0
        poi_id = 0
        pt_item_id = 0
        supplier_total = {}
        supply_last = {}
        stat = {"COMPLETED": 0, "PARTIAL": 0, "APPROVED": 0, "SUBMITTED": 0, "TERMINATED": 0, "DRAFT": 0}
        for po in range(1, po_n + 1):
            sid, sname = rng.choice(self.suppliers)
            ct = self._ops_by_time(rng, day_from, day_to, 8, 15)
            r = rng.random()
            if r < 0.78:
                status = "COMPLETED"
            elif r < 0.86:
                status = "PARTIAL"
            elif r < 0.92:
                status = "APPROVED"
            elif r < 0.96:
                status = "SUBMITTED"
            elif r < 0.985:
                status = "TERMINATED"
            else:
                status = "DRAFT"
            stat[status] += 1
            k = rng.randint(1, 5)
            lines = []
            for gid in rng.sample(self.goods_ids, k):
                g = self.goods[gid]
                price_c = max(10, int(g[4] * rng.uniform(0.97, 1.03)))
                qty = rng.randint(30, 400) if not g[2] else rng.randint(5, 60)  # 称重按 kg
                lines.append((gid, qty, price_c))
            total = sum(q * p for _, q, p in lines)
            key = (ct.date(), sid)
            po_seq[key] = po_seq.get(key, 0) + 1
            no = "PO%s%02d%04d" % (d_str(ct.date()).replace("-", ""), sid, po_seq[key] % 10000)
            submit = ct + timedelta(minutes=rng.randint(30, 180))
            audit = submit + timedelta(minutes=rng.randint(30, 600))
            expect = (ct + timedelta(days=rng.choice([2, 3, 5, 7]))).date()
            completed = status in ("COMPLETED", "PARTIAL")
            recv_time = audit + timedelta(days=rng.randint(1, 4), hours=rng.randint(0, 8)) if completed else None
            received_amount = 0
            return_amount = 0
            recv_id = None
            if completed:
                recv_id = po  # 一张采购单一张入库单（简化，PAYLOAD 与 poi 关联一致即可）
                lines_recv = []
                for lidx, (gid, qty, price_c) in enumerate(lines, start=1):
                    if status == "PARTIAL" and lidx == len(lines):
                        qty_r = max(1, qty * 60 // 100)
                    else:
                        qty_r = max(1, qty + rng.randint(-2, 3))
                    price_r = max(10, int(price_c * rng.uniform(0.98, 1.02)))
                    amount_r = qty_r * price_r
                    received_amount += amount_r
                    lines_recv.append((gid, qty_r, price_r, price_c, qty, amount_r))
                rkey = (recv_time.date(), 1)
                rec_seq[rkey] = rec_seq.get(rkey, 0) + 1
                rec_no = "RK%s%04d" % (d_str(recv_time.date()).replace("-", ""), rec_seq[rkey] % 10000)
                self.w.insert("receipt_order", ro_cols, "(%d, %s, %d, %d, %s, '赵库管', NULL, %s, %s, '仿真数据')" % (
                    recv_id, sql_str(rec_no), po, sid, sql_str(dt_str(recv_time)),
                    sql_str("DN%06d" % rng.randint(1, 999999)), fmt_cents(received_amount)))
                events.append(("PURCHASE", recv_time, (recv_id, lines_recv)))
                for gid, qty_r, price_r, price_po, qty_po, amount_r in lines_recv:
                    roi_id += 1
                    poi_id += 1
                    self.w.insert("receipt_order_item", roi_cols, "(%d, %d, %d, %d, %s, %s, %s)" % (
                        roi_id, recv_id, poi_id, gid, fmt_qty(qty_r * 1000, self.goods[gid][2]),
                        fmt_cents(price_r), fmt_cents(amount_r)))
                    self.w.insert("purchase_order_item", poi_cols, "(%d, %d, %d, %s, %s, %s, %s, 0)" % (
                        poi_id, po, gid, fmt_qty(qty_po * 1000, self.goods[gid][2]),
                        fmt_cents(price_po), fmt_cents(qty_po * price_po),
                        fmt_qty(qty_r * 1000, self.goods[gid][2])))
                    supplier_total[sid] = supplier_total.get(sid, 0) + amount_r
                    supply_last[sid] = recv_time
                # 部分采购退货（约 4% 的已完成采购单）
                if rng.random() < 0.04:
                    gid, qty_po, price_po = rng.choice(lines)
                    qty_rt = max(1, qty_po * rng.randint(20, 50) // 100)
                    pt_item_id += 1
                    t_rt = recv_time + timedelta(days=rng.randint(2, 10))
                    if t_rt.date() <= self.period_end:
                        pt_seq[(t_rt.date(), sid)] = pt_seq.get((t_rt.date(), sid), 0) + 1
                        pt_no = "PT%s%02d%04d" % (d_str(t_rt.date()).replace("-", ""), sid, pt_seq[(t_rt.date(), sid)] % 10000)
                        amt_rt = qty_rt * price_po
                        self.w.insert("purchase_return_order", pt_cols,
                                      "(%d, %s, %d, %d, 'APPROVED', '临近保质期退回', %s, '李采购', %s, '王店长', %s, NULL)" % (
                                          pt_item_id, sql_str(pt_no), po, sid, fmt_cents(amt_rt),
                                          sql_str(dt_str(t_rt)), sql_str(dt_str(t_rt + timedelta(hours=2)))))
                        self.w.insert("purchase_return_item", pt_item_cols, "(%d, %d, %d, %d, %s, %s, %s)" % (
                            pt_item_id, pt_item_id, poi_id, gid, fmt_qty(qty_rt * 1000, self.goods[gid][2]),
                            fmt_cents(price_po), fmt_cents(amt_rt)))
                        events.append(("PUR_RETURN", t_rt + timedelta(hours=2), (gid, qty_rt, price_po)))
                        return_amount += amt_rt
            # 采购单主表
            if completed:
                sr = rng.random()
                if sr < 0.7:
                    settle_status, settle_amount, settle_date, settle_method = "SETTLED", received_amount - return_amount, recv_time.date(), "MONTHLY"
                elif sr < 0.9:
                    settle_status, settle_amount = "PARTIAL", int((received_amount - return_amount) * 0.5), 
                    settle_date, settle_method = recv_time.date(), "MONTHLY"
                else:
                    settle_status, settle_amount, settle_date, settle_method = "UNSETTLED", None, None, None
            else:
                settle_status, settle_amount, settle_date, settle_method = "UNSETTLED", None, None, None
            po_vals = [
                str(po), sql_str(no), str(sid), sql_str(status), "'李采购'", sql_str(dt_str(ct)),
                sql_str(dt_str(submit)) if status != "DRAFT" else "NULL",
                sql_str("王店长") if status not in ("SUBMITTED", "DRAFT") else "NULL",
                sql_str(dt_str(audit)) if status not in ("SUBMITTED", "DRAFT") else "NULL",
                sql_str(d_str(expect)), fmt_cents(total), fmt_cents(received_amount), fmt_cents(return_amount),
                sql_str(settle_status), "NULL" if settle_amount is None else fmt_cents(settle_amount),
                sql_str(settle_method) if settle_method else "NULL",
                sql_str(d_str(settle_date)) if settle_date else "NULL",
                sql_str("王店长") if settle_amount is not None else "NULL",
                "0", sql_str("超期作废" if status == "TERMINATED" else "仿真数据"),
            ]
            self.w.insert("purchase_order", po_cols, "(%s)" % ", ".join(po_vals))
            # 未完成采购单的明细（入库单明细在 completed 分支已写；此处补充未入库单的采购明细）
            if not completed:
                for gid, qty, price_c in lines:
                    poi_id += 1
                    self.w.insert("purchase_order_item", poi_cols, "(%d, %d, %d, %s, %s, %s, 0, 0)" % (
                        poi_id, po, gid, fmt_qty(qty * 1000, self.goods[gid][2]),
                        fmt_cents(price_c), fmt_cents(qty * price_c)))
        events.sort(key=lambda e: e[1])
        self.supplier_total = supplier_total
        self.supply_last = supply_last
        self.log("采购数据生成完毕（%d 单, 状态分布 %s，%.1fs）" % (po_n, stat, time.time() - self.t0))
        return events

    def gen_loss_events(self):
        """报损/报溢单（单商品、店长审核）及其库存流水"""
        ln = self.scaled_table(self.args.loss_orders)
        self.log("生成报损报溢单 %d..." % ln)
        rng = self.rng
        events = []
        lo_cols = ("id, order_no, biz_type, goods_id, quantity, cost_price, amount, reason, apply_name,"
                   " status, auditor_name, audit_time, remark")
        seq = {}
        for i in range(1, ln + 1):
            t = self._ops_by_time(rng, self.period_start, self.period_end, 8, 20)
            gid = rng.choice(self.goods_ids)
            g = self.goods[gid]
            overflow = rng.random() < 0.15
            qty = rng.randint(1, 8) if not g[2] else round(rng.uniform(0.1, 1.5), 3)
            cost_c = g[4]
            qty_milli = int(qty * 1000) if g[2] else qty * 1000
            amount = half_up(cost_c * qty_milli, 1000)
            audited = rng.random() < 0.9
            seq[(t.date(), "BY" if overflow else "BS")] = seq.get((t.date(), "BY" if overflow else "BS"), 0) + 1
            no = "%s%s%04d" % ("BY" if overflow else "BS", d_str(t.date()).replace("-", ""),
                               seq[(t.date(), "BY" if overflow else "BS")] % 10000)
            self.w.insert("loss_order", lo_cols,
                          "(%d, %s, %s, %d, %s, %s, %s, %s, '赵库管', %s, %s, %s, '仿真数据')" % (
                              i, sql_str(no), sql_str("OVERFLOW" if overflow else "LOSS"), gid,
                              fmt_qty(qty_milli, g[2]), fmt_cost4(cost_c), fmt_cents(amount),
                              sql_str(self.rng.choices(LOSS_REASONS, LOSS_REASON_WEIGHTS)[0]),
                              sql_str("AUDITED" if audited else "PENDING"),
                              sql_str("王店长") if audited else "NULL",
                              sql_str(dt_str(t + timedelta(hours=2))) if audited else "NULL"))
            if audited:
                events.append(("OVERFLOW" if overflow else "LOSS", t + timedelta(hours=2),
                               (gid, qty_milli, cost_c)))
        events.sort(key=lambda e: e[1])
        self.log("报损报溢生成完毕（%.1fs）" % (time.time() - self.t0))
        return events
    # ---------------- 阶段 5：销售主循环 ----------------

    def load_events(self, events):
        self.other_events = events
        self.other_idx = 0

    def flush_other_events(self, before_t):
        evs = self.other_events
        while self.other_idx < len(evs) and evs[self.other_idx][1] <= before_t:
            kind, t, payload = evs[self.other_idx]
            self.other_idx += 1
            if kind == "PURCHASE":
                recv_id, lines = payload
                for gid, qty_r, price_r, price_po, qty_po, amount_r in lines:
                    delta = qty_r * 1000
                    self.apply_ledger(gid, "PURCHASE_IN", delta, price_r, "RK", None, "赵库管", t)
            elif kind == "PUR_RETURN":
                gid, qty_rt, price_po = payload
                self.apply_ledger(gid, "PURCHASE_RETURN", -qty_rt * 1000, price_po, "PT", None, "赵库管", t)
            elif kind == "LOSS":
                gid, qty_milli, cost_c = payload
                self.apply_ledger(gid, "LOSS_OUT", -qty_milli, cost_c, "SZ", None, "赵库管", t)
            elif kind == "OVERFLOW":
                gid, qty_milli, cost_c = payload
                self.apply_ledger(gid, "OVERFLOW_IN", qty_milli, cost_c, "BY", None, "赵库管", t)
            elif kind == "RECHARGE":
                mid, amt, pay_type, name = payload
                st = self.member_state[mid]
                st[1] += amt
                self.balance_rec_id += 1
                self.w.insert("balance_record",
                              "id, member_id, change_type, change_amount, after_balance, pay_type,"
                              " biz_no, operator_name, authorizer_name, create_time, remark",
                              "(%d, %d, 'RECHARGE', %s, %s, %s, NULL, '陈收银', '王店长', %s, '仿真充值')" % (
                                  self.balance_rec_id, mid, fmt_cents(amt), fmt_cents(st[1]),
                                  sql_str(pay_type), sql_str(dt_str(t))))

    def apply_ledger(self, gid, record_type, delta_milli, unit_cost_c, biz_type, biz_no, operator, t):
        """写一条库存流水并推进结存；不足时收缩/跳过量（保证不倒挂，AC-04 恒成立）"""
        g = self.goods[gid]
        cur = self.qty_milli[gid]
        if delta_milli < 0 and cur + delta_milli < 0:
            delta_milli = -cur  # 收缩为恰好出清
            if delta_milli == 0:
                return None
        after = cur + delta_milli
        self.qty_milli[gid] = after
        self.ledger_id += 1
        self.w.insert("inventory_record",
                      "id, goods_id, store_id, record_type, change_qty, before_qty, after_qty, unit_cost,"
                      " biz_type, biz_no, operator_name, operate_time, remark",
                      "(%d, %d, 1, %s, %s, %s, %s, %s, %s, %s, %s, %s, NULL)" % (
                          self.ledger_id, gid, sql_str(record_type), fmt_qty(delta_milli, g[2]),
                          fmt_qty(cur, g[2]), fmt_qty(after, g[2]), fmt_cost4(unit_cost_c),
                          sql_str(biz_type) if biz_type else "NULL", sql_str(biz_no) if biz_no else "NULL",
                          sql_str(operator), sql_str(dt_str(t))))
        self.last_change[gid] = t
        return after

    def run_sales(self):
        self.log("开始生成销售数据（共 %d 单）..." % self.args.orders)
        rng = self.rng
        args = self.args
        self.ledger_id = 10**9  # 占用独立 id 段，避免与后续事件重号
        self.last_change = {}
        self.balance_rec_id = 10**9
        self.point_rec_id = 10**9
        # 启用会员（status=1）用于随机选客
        valid_members = [(m[0], m[1], m[2], m[3]) for m in self.members if m[4] == 1]
        # 交班统计：shift_key=(day_idx, cashier_idx) -> [order_count, payable, cash, bank, mobile, balance, return_amt, start, end]
        self.shifts = {}
        self.shift_ids = {}
        order_id = 0
        item_id = 10**9
        pay_id = 10**9
        ret_id = 0
        no_seq = {}
        # 退货事件堆：(return_time, seq, payload)
        ret_heap = []
        day_list = [self.period_start + timedelta(days=i) for i in range(self.total_days + 1)]
        counts = self.allocate_counts(day_list)
        # 门店营业时段：08:00 ~ 22:00，午晚高峰
        hour_cum = []
        hour_w = [0.020, 0.035, 0.060, 0.105, 0.120, 0.075, 0.050, 0.060, 0.080, 0.105, 0.100, 0.075, 0.050, 0.025]
        total_hw = sum(hour_w)
        acc = 0.0
        for wv in hour_w:
            acc += wv / total_hw
            hour_cum.append(acc)
        hour_cum[-1] = 1.0
        hours = list(range(8, 22))
        now = datetime.now()
        today = now.date()
        processed = 0
        t_progress = time.time()
        for di, day in enumerate(day_list):
            cnt = counts[di]
            if cnt <= 0:
                continue
            # 当天所有订单时间（排序，保证流水按时间推演）
            times = []
            max_hour = 22
            if day == today:
                max_hour = max(8, min(22, now.hour))
            for _ in range(cnt):
                h = hours[min(len(hours) - 1, bisect.bisect_left(hour_cum, rng.random()))]
                if h > max_hour:
                    h = max_hour - 1 if max_hour > 8 else 8
                times.append(datetime(day.year, day.month, day.day, h, rng.randint(0, 59), rng.randint(0, 59)))
            times.sort()
            self.flush_other_events(datetime(day.year, day.month, day.day, 7, 59, 0))
            for t in times:
                # 先落账所有到点的事件（采购/报损/充值）
                self.flush_other_events(t)
                while ret_heap and ret_heap[0][0] <= t:
                    _, _, payload = heapq.heappop(ret_heap)
                    self.emit_return(*payload)
                order_id += 1
                cashier_i = self.pick_counter(rng)
                cashier_id, cashier_name = self.cashiers[cashier_i]
                counter_no = cashier_i + 1
                key = (d_str(day).replace("-", ""), counter_no)
                no_seq[key] = no_seq.get(key, 0) + 1
                seq_no = no_seq[key]
                if seq_no > 9999:
                    raise SystemExit("单日单收银台流水超过 9999，请增大 --months 或减少 --orders")
                order_no = "XS%s%02d%04d" % (key[0], counter_no, seq_no)
                has_member = rng.random() < args.member_order_ratio
                member = rng.choice(valid_members) if has_member else None
                mid, m_rate_bp, m_point_bp = (member[0], member[1], member[2]) if member else (0, 10000, 10000)
                k = self.pick_item_count(rng)
                gids = self.pick_goods(rng, k)
                ret_plan = rng.random()
                lines = self.calc_lines(rng, gids, t, mid if member else None, m_rate_bp)
                if not lines:
                    order_id -= 1
                    continue
                res = self.emit_order(order_id, order_no, counter_no, cashier_id, cashier_name, day, t,
                                      lines, mid if member else None, m_rate_bp, m_point_bp, rng)
                pay_ok, payable, pay_rows = res[0], res[1], res[2]
                # 交班统计累计
                day_key = (di, cashier_i)
                st = self.shifts.get(day_key)
                if st is None:
                    # [单数, 应收合计, 现金, 银行卡, 移动支付, 储值, 退货额, 开班, 交班, 现金退款]
                    st = [0, 0, 0, 0, 0, 0, 0,
                          datetime(day.year, day.month, day.day, 8, 0, 0),
                          datetime(day.year, day.month, day.day, 22, 30, 0), 0]
                    self.shifts[day_key] = st
                st[0] += 1
                st[1] += payable
                for pt_, amt_ in pay_rows:
                    if pt_ == "CASH":
                        st[2] += amt_
                    elif pt_ == "BANK":
                        st[3] += amt_
                    elif pt_ == "MOBILE":
                        st[4] += amt_
                    elif pt_ == "BALANCE":
                        st[5] += amt_
                # 退货计划（1.2% 全退 / 0.8% 部分退）：生成返还事件压入堆，按时间顺延落账
                if ret_plan < args.return_ratio and any(self.goods[g][6] == 1 for g in gids):
                    rt = t + timedelta(days=rng.randint(0, 25), hours=rng.randint(0, 12))
                    if rt.date() > self.period_end:
                        rt = datetime(self.period_end.year, self.period_end.month, self.period_end.day,
                                      min(21, now.hour), rng.randint(0, 59)) - timedelta(minutes=rng.randint(30, 300))
                    if rt > t:
                        full = rng.random() < 0.6
                        payload = (order_id, order_no, counter_no, cashier_id, cashier_name, t, day, rt,
                                   res, full, mid if member else None, m_rate_bp)
                        heapq.heappush(ret_heap, (rt, order_id, payload))
                processed += 1
                if processed % 100000 == 0:
                    el = time.time() - t_progress
                    self.log("进度 %d/%d（本段 %.0fs，累计 %.0fs）" % (processed, args.orders, el, time.time() - self.t0))
                    t_progress = time.time()
        # 收尾：剩余事件与退货
        end_t = datetime(self.period_end.year, self.period_end.month, self.period_end.day, 23, 59, 59)
        self.flush_other_events(end_t)
        while ret_heap:
            _, _, payload = heapq.heappop(ret_heap)
            self.emit_return(*payload)
        self.log("销售主循环完成（%d 单，%.1fs）" % (processed, time.time() - self.t0))

    def allocate_counts(self, day_list):
        """按星期与随机扰动把总单量分配到每一天（含今日营业时段折算）"""
        rng = self.rng
        wd = [1.0, 0.90, 0.90, 0.94, 1.00, 1.20, 1.28]  # 周一..周日
        weights = [wd[d.weekday()] * rng.uniform(0.92, 1.08) for d in day_list]
        total_w = sum(weights)
        counts = [int(self.args.orders * wv / total_w) for wv in weights]
        # 余数按最大余数法补齐
        rem = self.args.orders - sum(counts)
        order_of_rem = sorted(range(len(weights)),
                              key=lambda i: (self.args.orders * weights[i] / total_w) - counts[i], reverse=True)
        for i in order_of_rem[:rem]:
            counts[i] += 1
        # 今日按已过去营业时段折算
        now = datetime.now()
        if day_list[-1] == now.date():
            frac = max(0.0, min(1.0, (now.hour - 8) / 14.0))
            counts[-1] = int(counts[-1] * frac)
        return counts

    def pick_counter(self, rng):
        n = len(self.cashiers)
        r = rng.random()
        if n >= 4:
            if r < 0.28:
                return 0
            if r < 0.50:
                return 1
            if r < 0.68:
                return 2
            if r < 0.82:
                return 3
            if r < 0.92:
                return 4
            return rng.randrange(n)
        return rng.randrange(n)

    def pick_item_count(self, rng):
        r = rng.random()
        if r < 0.30:
            return 1
        if r < 0.55:
            return 2
        if r < 0.74:
            return 3
        if r < 0.88:
            return 4
        if r < 0.96:
            return 5
        return 6

    def pick_goods(self, rng, k):
        """按 Zipf 权重无放回抽样 k 个商品"""
        cum = self.cum_weights
        picked = set()
        out = []
        while len(out) < k:
            r = rng.random() * self.total_w
            idx = bisect.bisect_left(cum, r)
            if idx >= len(cum):
                idx = len(cum) - 1
            gid = self.goods_ids[idx]
            if gid in picked:
                continue
            picked.add(gid)
            out.append(gid)
        return out

    def calc_lines(self, rng, gids, t, mid, m_rate_bp):
        """逐行计算金额（BR-SALE-03）：与后端一致——行金额四舍五入、优惠按行分摊尾差入最后一行"""
        lines = []
        override_gid = gids[-1] if rng.random() < 0.005 else None
        for gid in gids:
            g = self.goods[gid]
            weighed = g[2] == 1
            price_c = self.price_at(gid, t, mid is not None)
            orig_price_c = price_c
            override_new = None
            if gid == override_gid:
                # 店长改价：成交价下调，original_unit_price 保留改价前取价值（FR-SALE-07）
                override_new = max(10, int(price_c * rng.uniform(0.75, 0.95)))
                price_c = override_new
            if weighed:
                qty_milli = rng.randint(200, 3000)
            else:
                r = rng.random()
                qty_milli = (1 if r < 0.62 else (2 if r < 0.88 else 3)) * 1000
            amount_c = half_up(price_c * qty_milli, 1000)
            # 库存不足则收缩数量/跳过该行（保证结存不为负）
            avail = self.qty_milli[gid]
            if qty_milli > avail:
                qty_milli = avail
                if qty_milli < (50 if weighed else 1000):
                    continue
                amount_c = half_up(price_c * qty_milli, 1000)
            cost_c = g[4]
            cost_amount_c = half_up(cost_c * qty_milli, 1000)
            lines.append([gid, g, price_c, orig_price_c if override_new is not None else None,
                          qty_milli, amount_c, cost_amount_c])
        if not lines:
            return []
        # 整单折扣（约 1.5%）：最低 0.7 已在生成端保证，原因与授权人齐全
        order_rate_bp = 10000
        order_discount_reason = None
        if rng.random() < 0.015:
            order_rate_bp = rng.randint(8000, 9500)
            order_discount_reason = rng.choice(["会员日促销", "临期商品折让", "店长特批", "客户关怀"])
        total_rate_bp = m_rate_bp * order_rate_bp // 10000
        goods_amount = sum(x[5] for x in lines)
        target_discount = half_up(goods_amount * (10000 - total_rate_bp), 10000)
        allocated = 0
        for i in range(len(lines) - 1):
            d = half_up(lines[i][5] * (10000 - total_rate_bp), 10000)
            lines[i].append(d)
            allocated += d
        lines[-1].append(target_discount - allocated)
        return (lines, goods_amount, target_discount, target_discount, m_rate_bp, order_rate_bp,
                order_discount_reason, total_rate_bp)

    def emit_order(self, order_id, order_no, counter_no, cashier_id, cashier_name, day, t,
                   calc, mid, m_rate_bp, m_point_bp, rng):
        """落库一笔销售：单头/明细/收款/库存流水/会员联动；返回可复用的退货数据"""
        (lines, goods_amount, target_discount, _td, _mr, order_rate_bp, order_discount_reason,
         total_rate_bp) = calc
        member_discount = half_up(goods_amount * (10000 - m_rate_bp), 10000) if mid else 0
        order_discount = target_discount - member_discount
        payable = goods_amount - target_discount
        authorizer = None
        has_override = any(x[3] is not None for x in lines)
        if (order_rate_bp < 10000 or has_override) and rng.random() < 0.85:
            authorizer = MANAGER_NAME
        # 收款：储值仅限会员且不超余额（不足时按比例拆分为储值 + 其他方式）
        pay_rows = []
        balance_paid = 0
        if mid and rng.random() < 0.10:
            bal = self.member_state[mid][1]
            if bal >= payable:
                pay_rows.append(("BALANCE", payable))
                balance_paid = payable
            elif bal >= 500:
                part = (bal // 100) * 100
                pay_rows.append(("BALANCE", part))
                balance_paid = part
        if not balance_paid:
            pay_type = rng.choices(DICT_PAY_TYPES[:3], PAY_WEIGHTS[:3])[0]
            pay_rows.append((pay_type, payable))
        elif payable - balance_paid > 0:
            pay_rows.append((rng.choices(DICT_PAY_TYPES[:3], PAY_WEIGHTS[:3])[0], payable - balance_paid))
        # 现金整付偶尔找零（给出下一个 5 元面额）
        change = 0
        if len(pay_rows) == 1 and pay_rows[0][0] == "CASH" and payable < 10000 and rng.random() < 0.35:
            paid = (payable // 500 + 1) * 500
            change = paid - payable
            pay_rows = [("CASH", paid)]
        paid = sum(a for _, a in pay_rows)
        # 件数口径与 SalesCheckoutService 一致：整数量行按整数值计（含恰好整数的称重行），
        # 非整数量行（仅称重商品可能出现）计 1 件
        item_count = sum(x[4] // 1000 if x[4] % 1000 == 0 else 1 for x in lines)
        cur_date = t.date()
        # 班次 id 公式：(day_index × 收银员数) + 收银员序号 + 1，与班次表落库保持一致
        day_idx = (cur_date - self.period_start).days
        cidx = next(i for i, cc in enumerate(self.cashiers) if cc[0] == cashier_id)
        shift_id = day_idx * len(self.cashiers) + cidx + 1
        today = datetime.now().date()
        self.w.insert("sales_order",
                      "id, order_no, store_id, counter_no, cashier_id, cashier_name, shift_id, member_id,"
                      " sale_time, business_date, item_count, goods_amount, member_discount_amount,"
                      " order_discount_amount, discount_amount, payable_amount, paid_amount, change_amount,"
                      " order_discount_rate, discount_reason, cost_amount, profit_amount, return_status,"
                      " authorizer_name, remark",
                      "(%d, %s, 1, %d, %d, %s, %d, %s, %s, %s, %d, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, 'NONE', %s, NULL)" % (
                          order_id, sql_str(order_no), counter_no, cashier_id, sql_str(cashier_name), shift_id,
                          str(mid) if mid else "NULL", sql_str(dt_str(t)),
                          sql_str(d_str(day)), item_count, fmt_cents(goods_amount), fmt_cents(member_discount),
                          fmt_cents(order_discount), fmt_cents(target_discount), fmt_cents(payable),
                          fmt_cents(paid), fmt_cents(change),
                          fmt_rate(order_rate_bp) if order_rate_bp < 10000 else "NULL",
                          sql_str(order_discount_reason) if order_discount_reason else "NULL",
                          fmt_cents(sum(x[6] for x in lines)),
                          fmt_cents(payable - sum(x[6] for x in lines)),
                          sql_str(authorizer) if authorizer else "NULL"))
        # 明细 + 库存出库流水
        item_id = order_id * 10
        ret_items = []
        for idx, (gid, g, price_c, override, qty_milli, amount_c, cost_amount_c, _disc) in enumerate(lines):
            item_id += 1
            paid_line = amount_c - sum_disc(lines, idx)
            self.w.insert("sales_order_item",
                          "id, order_id, goods_id, goods_name, barcode, unit_price, original_unit_price,"
                          " quantity, is_weighed, net_weight, amount, discount_rate, discount_amount,"
                          " paid_amount, cost_price, cost_amount, profit_amount, returned_qty, line_type,"
                          " price_reason",
                          "(%d, %d, %d, %s, %s, %s, %s, %s, %d, %s, %s, %s, %s, %s, %s, %s, %s, 0, 'NORMAL', %s)" % (
                              item_id, order_id, gid, sql_str(g[0]), sql_str(g[1]), fmt_cents(price_c),
                              fmt_cents(override) if override is not None else "NULL",
                              fmt_qty(qty_milli, g[2]), g[2],
                              fmt_qty(qty_milli, g[2]) if g[2] else "NULL",
                              fmt_cents(amount_c), fmt_rate(total_rate_bp),
                              fmt_cents(sum_disc(lines, idx)), fmt_cents(paid_line),
                              fmt_cost4(g[4]), fmt_cents(cost_amount_c),
                              fmt_cents(paid_line - cost_amount_c),
                              sql_str(rng.choice(["临期折价", "瑕疵折价", "店长特批"])) if override is not None else "NULL"))
            self.apply_ledger(gid, "SALE_OUT", -qty_milli, g[4], "XS", order_no, cashier_name, t)
            ret_items.append([item_id, gid, g, qty_milli, paid_line, g[4], price_c])
        # 收款记录
        pay_seq = 0
        for pt_, amt_ in pay_rows:
            pay_seq += 1
            self.w.insert("payment_record", "id, order_id, pay_type, amount, pay_time, operator_name",
                          "(%d, %d, %s, %s, %s, %s)" % (order_id * 3 + pay_seq, order_id, sql_str(pt_),
                                                        fmt_cents(amt_), sql_str(dt_str(t)), sql_str(cashier_name)))
        # 会员联动：累计消费/次数/积分/储值扣减（BR-MEM-05/06/07）
        if mid:
            st = self.member_state[mid]
            base_c = payable - balance_paid
            points = (base_c * m_point_bp) // (100 * 10000)
            st[0] += points
            self.point_rec_id += 1
            self.w.insert("point_record",
                          "id, member_id, change_type, change_value, after_value, biz_no, operator_name,"
                          " create_time, remark",
                          "(%d, %d, 'CONSUME', %d, %d, %s, %s, %s, NULL)" % (
                              self.point_rec_id, mid, points, st[0], sql_str(order_no),
                              sql_str(cashier_name), sql_str(dt_str(t))))
            if balance_paid:
                st[1] -= balance_paid
                self.balance_rec_id += 1
                self.w.insert("balance_record",
                              "id, member_id, change_type, change_amount, after_balance, pay_type, biz_no,"
                              " operator_name, authorizer_name, create_time, remark",
                              "(%d, %d, 'CONSUME', %s, %s, NULL, %s, %s, NULL, %s, '储值消费')" % (
                                  self.balance_rec_id, mid, fmt_cents(-balance_paid), fmt_cents(st[1]),
                                  sql_str(order_no), sql_str(cashier_name), sql_str(dt_str(t))))
        return (True, payable, pay_rows, order_id, order_no, counter_no, cashier_id, cashier_name, t, day,
                ret_items, mid, m_rate_bp, m_point_bp, payable, authorizer)

    def emit_return(self, order_id, order_no, counter_no, cashier_id, cashier_name, sale_t, day, rt,
                    res, full, mid, m_rate_bp):
        """退货单：按原实付单价比例退款（BR-SALE-06），成本快照回冲，库存回补、会员回退"""
        (pay_ok, payable, pay_rows, _oid, _ono, _cn, _cid, _cname, _t, _day, ret_items, _mid, _mr, _mp,
         _payable, _auth) = res
        rng = self.rng
        cand = [it for it in ret_items if self.goods[it[1]][6] == 1]
        if not cand:
            return
        self.return_seq += 1
        rt_key = d_str(rt.date()).replace("-", "")
        key = (rt_key, counter_no)
        self.ret_no_seq[key] = self.ret_no_seq.get(key, 0) + 1
        ret_no = "XT%s%02d%04d" % (rt_key, counter_no, self.ret_no_seq[key] % 10000)
        if full:
            sel = [(it, it[3]) for it in cand]
        else:
            sel_items = rng.sample(cand, max(1, len(cand) - 1))
            sel = []
            for it in sel_items:
                if it[3] >= 2000 and rng.random() < 0.5:
                    if self.goods[it[1]][2]:
                        q = max(100, (it[3] * rng.randint(30, 70) // 100) // 100 * 100)     # 称重：0.1 精度
                    else:
                        q = max(1000, (it[3] * rng.randint(30, 70) // 100) // 1000 * 1000)   # 普通：整件
                    sel.append((it, q))
                else:
                    sel.append((it, it[3]))
        total_refund = 0
        total_cost = 0
        lines = []
        for it, q in sel:
            item_id, gid, g, qty_milli, paid_c, cost_c, price_c = it
            refund = half_up(paid_c * q, qty_milli)
            # 退款单价 = 实付金额 ÷ 数量（BR-SALE-06，对齐 SalesReturnService：paidAmount/quantity 四舍五入 2 位）
            refund_price = half_up(paid_c * 1000, qty_milli)
            cost_amount = half_up(cost_c * q, 1000)
            total_refund += refund
            total_cost += cost_amount
            lines.append((item_id, gid, g, q, refund, refund_price, cost_c, cost_amount))
        # 退款方式：优先原单主要方式（储值退款退回储值）
        balance_paid = sum(a for pt_, a in pay_rows if pt_ == "BALANCE")
        refund_type = "BALANCE" if (balance_paid >= payable and rng.random() < 0.7) else \
            next((pt_ for pt_, _ in pay_rows if pt_ != "BALANCE"), pay_rows[0][0])
        need_auth = total_refund > 20000 or rng.random() < 0.05
        reason = rng.choices(DICT_RETURN_REASONS, RETURN_REASON_WEIGHTS)[0]
        self.w.insert("return_order",
                      "id, order_no, store_id, sales_order_id, member_id, return_reason, reason_remark,"
                      " refund_amount, refund_type, cashier_name, authorizer_name, authorize_time,"
                      " return_time, business_date",
                      "(%d, %s, 1, %d, %s, %s, NULL, %s, %s, %s, %s, %s, %s, %s)" % (
                          self.return_seq, sql_str(ret_no), order_id, str(mid) if mid else "NULL",
                          sql_str(reason), fmt_cents(total_refund), sql_str(refund_type),
                          sql_str(cashier_name), sql_str(MANAGER_NAME) if need_auth else "NULL",
                          sql_str(dt_str(rt - timedelta(minutes=5))) if need_auth else "NULL",
                          sql_str(dt_str(rt)), sql_str(d_str(rt.date()))))
        for item_id, gid, g, q, refund, refund_price, cost_c, cost_amount in lines:
            self.ret_item_id += 1
            self.w.insert("return_order_item",
                          "id, return_id, sales_item_id, goods_id, return_qty, refund_price, refund_amount,"
                          " cost_price, cost_amount",
                          "(%d, %d, %d, %d, %s, %s, %s, %s, %s)" % (
                              self.ret_item_id, self.return_seq, item_id, gid, fmt_qty(q, g[2]),
                              fmt_cents(refund_price), fmt_cents(refund), fmt_cost4(cost_c),
                              fmt_cents(cost_amount)))
            self.deferred.append("UPDATE sales_order_item SET returned_qty = returned_qty + %s WHERE id = %d;\n" % (
                fmt_qty(q, g[2]), item_id))
            self.apply_ledger(gid, "SALE_RETURN", q, cost_c, "XT", ret_no, cashier_name, rt)
        # 全部可退行均已退且计划为全退时记 ALL，否则 PARTIAL
        self.deferred.append("UPDATE sales_order SET return_status = %s WHERE id = %d;\n" % (
            sql_str("ALL" if full and len(cand) == len(ret_items) else "PARTIAL"), order_id))
        # 交班统计累计：退货归属退货当日同收银台班次，现金退款冲减应收现金
        shift_st = self.shifts.get(((rt.date() - self.period_start).days, counter_no - 1))
        if shift_st is not None:
            shift_st[6] += total_refund
            if refund_type == "CASH":
                shift_st[9] += total_refund
        # 会员回退：积分按退款比例扣回（原单积分 × 退款 / 应收，且不超过当前积分）、储值退回
        if mid:
            st = self.member_state[mid]
            if refund_type == "BALANCE":
                st[1] += total_refund
                self.balance_rec_id += 1
                self.w.insert("balance_record",
                              "id, member_id, change_type, change_amount, after_balance, pay_type, biz_no,"
                              " operator_name, authorizer_name, create_time, remark",
                              "(%d, %d, 'REFUND', %s, %s, NULL, %s, %s, NULL, %s, '退货退回储值')" % (
                                  self.balance_rec_id, mid, fmt_cents(total_refund), fmt_cents(st[1]),
                                  sql_str(ret_no), sql_str(cashier_name), sql_str(dt_str(rt))))
            orig_balance_paid = sum(a for pt_, a in pay_rows if pt_ == "BALANCE")
            orig_points = (max(0, payable - orig_balance_paid) * _mp) // (100 * 10000)
            deduct = min(st[0], (orig_points * total_refund) // payable) if payable > 0 else 0
            if deduct:
                st[0] -= deduct
                self.point_rec_id += 1
                self.w.insert("point_record",
                              "id, member_id, change_type, change_value, after_value, biz_no, operator_name,"
                              " create_time, remark",
                              "(%d, %d, 'RETURN', %d, %d, %s, %s, %s, NULL)" % (
                                  self.point_rec_id, mid, -deduct, st[0], sql_str(ret_no),
                                  sql_str(cashier_name), sql_str(dt_str(rt))))

    # ---------------- 阶段 6：班次与派生数据回写 ----------------

    def emit_shifts(self):
        self.log("生成收银班次 %d 条..." % len(self.shifts))
        cols = ("id, cashier_id, cashier_name, counter_no, start_time, end_time, order_count, sales_amount,"
                " cash_amount, bank_amount, mobile_amount, balance_amount, return_amount, expected_cash, status")
        for (di, cidx), st in sorted(self.shifts.items()):
            cashier_id, cashier_name = self.cashiers[cidx]
            shift_id = di * len(self.cashiers) + cidx + 1
            status = "CLOSED"
            net = st[1] - st[6]
            self.w.insert("cashier_shift", cols, "(%d, %d, %s, %d, %s, %s, %d, %s, %s, %s, %s, %s, %s, %s, %s)" % (
                shift_id, cashier_id, sql_str(cashier_name), cidx + 1, sql_str(dt_str(st[7])), sql_str(dt_str(st[8])),
                st[0], fmt_cents(net), fmt_cents(st[2]), fmt_cents(st[3]), fmt_cents(st[4]), fmt_cents(st[5]),
                fmt_cents(st[6]), fmt_cents(st[2] - st[9]), sql_str(status)))

    def emit_final_updates(self):
        """回写派生数据：延迟更新、库存结存、会员聚合、供应商累计采购额与最近供货时间"""
        self.log("回写派生数据（延迟更新 %d 条 / 库存结存 / 会员 / 供应商）..." % len(self.deferred))
        # 1) 延迟的明细/单据回写（退货回冲，需在所有对应 INSERT 之后执行）
        for i in range(0, len(self.deferred), 20000):
            self.w.raw("".join(self.deferred[i:i + 20000]))
        self.deferred.clear()
        # 2) 库存结存：数量按流水推演结果，成本单价恒定（等价移动加权），成本金额 = 结存 × 成本
        inv_cols = "goods_id, store_id, quantity, cost_price, cost_amount, last_change_time"
        for gid, milli in self.qty_milli.items():
            g = self.goods[gid]
            cost_amount = half_up(g[4] * milli, 1000)
            lt = self.last_change.get(gid)
            self.w.insert("inventory", inv_cols, "(%d, 1, %s, %s, %s, %s)" % (
                gid, fmt_qty(milli, g[2]), fmt_cost4(g[4]), fmt_cents(cost_amount),
                sql_str(dt_str(lt)) if lt else "NULL"))
        # 3) 采购单明细已退数量（聚合回写，与采购退货单明细一致）
        self.w.raw("""
UPDATE purchase_order_item poi
LEFT JOIN (SELECT purchase_item_id, SUM(return_qty) AS rq FROM purchase_return_item GROUP BY purchase_item_id) t
       ON t.purchase_item_id = poi.id
SET poi.returned_qty = COALESCE(t.rq, 0);
""")
        # 4) 会员聚合：累计消费/次数/最近消费时间由单据聚合（SQL 端计算避免内存放大）
        m_from = 5000 + 1
        self.w.raw("""
UPDATE member m
LEFT JOIN (SELECT member_id, SUM(payable_amount) AS s FROM sales_order WHERE member_id IS NOT NULL GROUP BY member_id) so
       ON so.member_id = m.id
LEFT JOIN (SELECT member_id, SUM(refund_amount) AS r FROM return_order WHERE member_id IS NOT NULL GROUP BY member_id) ro
       ON ro.member_id = m.id
LEFT JOIN (SELECT member_id, COUNT(*) AS c, MAX(sale_time) AS lt FROM sales_order WHERE member_id IS NOT NULL GROUP BY member_id) ct
       ON ct.member_id = m.id
SET m.total_consume = GREATEST(0, COALESCE(so.s, 0) - COALESCE(ro.r, 0)),
    m.consume_times = COALESCE(ct.c, 0),
    m.last_consume_time = ct.lt
WHERE m.id >= %d;
""" % m_from)
        # 5) 积分/余额回写（内存推演值与流水一致）
        batch = 200
        member_ids = [m[0] for m in self.members]
        for i in range(0, len(member_ids), batch):
            chunk = member_ids[i:i + batch]
            ids = ",".join(str(mid) for mid in chunk)
            pts = " ".join("WHEN %d THEN %d" % (mid, self.member_state[mid][0]) for mid in chunk)
            bal = " ".join("WHEN %d THEN %s" % (mid, fmt_cents(self.member_state[mid][1])) for mid in chunk)
            self.w.raw("UPDATE member SET points = CASE id %s END, balance = CASE id %s END WHERE id IN (%s);\n"
                       % (pts, bal, ids))
        # 6) 会员等级按累计消费门槛重算（只升不降，BR-MEM-06 ONLY_UP）
        self.w.raw("""
UPDATE member m
SET m.level_id = GREATEST(m.level_id, COALESCE(
        (SELECT l.id FROM member_level l
          WHERE l.status = 1 AND l.threshold <= m.total_consume
          ORDER BY l.threshold DESC, l.id DESC LIMIT 1), m.level_id))
WHERE m.id >= %d;
""" % m_from)
        # 7) 供应商累计采购额（按入库单实收金额汇总）
        if self.supplier_total:
            ids = ",".join(str(sid) for sid in self.supplier_total)
            tot = " ".join("WHEN %d THEN %s" % (sid, fmt_cents(v)) for sid, v in self.supplier_total.items())
            self.w.raw("UPDATE supplier SET total_purchase_amount = CASE id %s END WHERE id IN (%s);\n"
                       % (tot, ids))
        # 8) 供货关系最近供货时间（该供应商最后一次入库时间）
        if self.supply_last:
            ids = ",".join(str(sid) for sid in self.supply_last)
            lst = " ".join("WHEN %d THEN %s" % (sid, sql_str(dt_str(t))) for sid, t in self.supply_last.items())
            self.w.raw("UPDATE supplier_goods SET last_supply_time = CASE supplier_id %s END WHERE supplier_id IN (%s);\n"
                       % (lst, ids))

    # ---------------- 主流程 ----------------

    def run(self):
        args = self.args
        rng = self.rng
        today = date.today()
        self.period_end = today
        # 起始日：约 --months 个月前
        m = args.months
        y = today.year
        mo = today.month - m
        while mo <= 0:
            mo += 12
            y -= 1
        try:
            self.period_start = date(y, mo, min(today.day, 28))
        except ValueError:
            self.period_start = date(y, mo, 1)
        self.total_days = (self.period_end - self.period_start).days
        self.return_seq = 10**9
        self.ret_item_id = 10**9
        self.ret_no_seq = {}
        self.ledger_id = 10**9
        self.balance_rec_id = 10**9
        self.point_rec_id = 10**9
        self.last_change = {}
        self.shifts = {}
        self.cashiers = []
        self.promotions = {}
        self.member_state = {}
        self.members = []
        self.deferred = []

        self.log("时间区间 %s ~ %s（%d 天），商品 %d，销售单 %d，会员 %d，种子 %d" % (
            d_str(self.period_start), d_str(self.period_end), self.total_days + 1,
            args.goods, args.orders, args.members, args.seed))
        w = self.w
        w.raw("-- SMMS 仿真数据（AC-D07）：由 tools/gen-perf-data.py 生成，seed=%d\n"
              "-- 规模：商品 %d / 销售单 %d / 时间 %s ~ %s\n"
              "SET NAMES utf8mb4;\nSET unique_checks = 0;\nSET foreign_key_checks = 0;\nSET autocommit = 0;\n" % (
                  args.seed, args.goods, args.orders, d_str(self.period_start), d_str(self.period_end)))
        if args.truncate:
            self.emit_truncate()
        self.emit_categories()
        self.emit_users()
        self.emit_brands()
        self.emit_suppliers()
        self.emit_goods()
        self.emit_members()
        purchase_events = self.gen_purchase_events()
        loss_events = self.gen_loss_events()
        recharge_events = [("RECHARGE", t, (mid, amt, pt_, None)) for (t, mid, amt, pt_) in self.recharge_events]
        all_events = purchase_events + loss_events
        if recharge_events:
            all_events = sorted(all_events + recharge_events, key=lambda e: e[1])
        self.load_events(all_events)
        self.run_sales()
        self.emit_shifts()
        self.emit_final_updates()
        w.raw("SET unique_checks = 1;\nSET foreign_key_checks = 1;\n")
        w.close()
        self.log("完成！共输出 %d 行数据，耗时 %.1fs" % (w.rows_written, time.time() - self.t0))
        self.log("各表行数：" + ", ".join("%s=%d" % (k, v) for k, v in sorted(self.w.stat.items())))


def sum_disc(lines, idx):
    """取该行的分摊优惠（lines[i] 末尾追加）"""
    return lines[idx][7] if len(lines[idx]) > 7 else 0


def main():
    ap = argparse.ArgumentParser(description="SMMS 仿真数据生成器（输出 SQL 到标准输出）")
    ap.add_argument("--goods", type=int, default=20000, help="商品数（默认 20000）")
    ap.add_argument("--orders", type=int, default=2000000, help="销售单数（默认 2000000）")
    ap.add_argument("--members", type=int, default=5000, help="会员数（默认 5000）")
    ap.add_argument("--suppliers", type=int, default=50, help="供应商数（默认 50）")
    ap.add_argument("--purchase-orders", type=int, default=3000, help="采购单数（默认 3000）")
    ap.add_argument("--loss-orders", type=int, default=3000, help="报损报溢单数（默认 3000）")
    ap.add_argument("--months", type=int, default=36, help="覆盖月数（默认 36）")
    ap.add_argument("--seed", type=int, default=20261006, help="随机种子（默认 20261006，可复现）")
    ap.add_argument("--batch", type=int, default=BATCH_ROWS, help="多值 INSERT 批大小")
    ap.add_argument("--fresh-ratio", type=float, default=0.08, help="称重/生鲜商品占比")
    ap.add_argument("--member-order-ratio", type=float, default=0.40, help="会员单占比")
    ap.add_argument("--return-ratio", type=float, default=0.02, help="退货单占销售单比例")
    ap.add_argument("--scale", type=float, default=1.0, help="辅助数据缩放系数（小规模联调用）")
    ap.add_argument("--truncate", action="store_true",
                    help="执行前清空既有业务数据（商品/单据/会员等，不影响系统账号/字典/参数）")
    args = ap.parse_args()
    gen = PerfDataGenerator(args)
    gen.run()


if __name__ == "__main__":
    main()
