#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""SMMS 全量仿真库一致性核验（AC-04 / AC-D07）

对已导入仿真数据（或实际业务库）的 MySQL 实例逐项执行 27 条不变量 SQL，
逐项输出 编号/核验项/违规数/耗时，任一违规以退出码 1 结束。

用法:
  python3 tools/gen-perf-check.py [--container smms-perf-selftest]
                                  [--user root] [--password testroot] [--database smms]

口径说明：各检查与后端服务实现一致（金额整数分四舍五入、件数=整数量行取整值+
称重行计 1、退款单价=原单实付÷数量、退货/收款/会员口径回填等），
对应《测试报告》§7「全量仿真数据一致性核验」。
"""
import argparse
import subprocess
import sys
import time

CHECKS = [
    ('B01', '流水链式连续(before=上条after, after=before+change)', """
        SELECT COUNT(*) FROM (
          SELECT after_qty, before_qty, change_qty,
                 LAG(after_qty) OVER (PARTITION BY goods_id ORDER BY id) AS prev_after
          FROM inventory_record) t
        WHERE (prev_after IS NOT NULL AND ABS(prev_after - before_qty) > 0.0005)
           OR ABS(after_qty - (before_qty + change_qty)) > 0.0005"""),
    ('B02', '期初流水自 0 起算', """
        SELECT COUNT(*) FROM (
          SELECT before_qty, ROW_NUMBER() OVER (PARTITION BY goods_id ORDER BY id) AS rn
          FROM inventory_record) t
        WHERE rn = 1 AND ABS(before_qty) > 0.0005"""),
    ('B03', '结存=Σ流水', """
        SELECT COUNT(*) FROM inventory i
        JOIN (SELECT goods_id, SUM(change_qty) s FROM inventory_record GROUP BY goods_id) r ON r.goods_id = i.goods_id
        WHERE ABS(i.quantity - r.s) > 0.0005"""),
    ('B04', '结存非负', "SELECT COUNT(*) FROM inventory WHERE quantity < 0"),
    ('B05', '库存成本=数量×成本单价',
     "SELECT COUNT(*) FROM inventory WHERE ABS(cost_amount - ROUND(quantity * cost_price, 2)) > 0.005"),
    ('B06', '销售单头=Σ明细（件数=整数量行取整值+称重行计1）', """
        SELECT COUNT(*) FROM sales_order o JOIN (
          SELECT order_id, SUM(CASE WHEN quantity = TRUNCATE(quantity, 0) THEN quantity ELSE 1 END) c,
                 SUM(amount) ga, SUM(discount_amount) da, SUM(cost_amount) ca, SUM(profit_amount) pa
          FROM sales_order_item GROUP BY order_id) i ON i.order_id = o.id
        WHERE o.item_count <> i.c OR ABS(o.goods_amount - i.ga) > 0.005 OR ABS(o.discount_amount - i.da) > 0.005
           OR ABS(o.cost_amount - i.ca) > 0.005 OR ABS(o.profit_amount - i.pa) > 0.005"""),
    ('B07', '销售金额恒等式', """
        SELECT COUNT(*) FROM sales_order
        WHERE ABS(payable_amount - (goods_amount - discount_amount)) > 0.005
           OR ABS(paid_amount - change_amount - payable_amount) > 0.005
           OR ABS(profit_amount - (payable_amount - cost_amount)) > 0.005
           OR ABS(discount_amount - (member_discount_amount + order_discount_amount)) > 0.005"""),
    ('B08', '明细行恒等式', """
        SELECT COUNT(*) FROM sales_order_item
        WHERE ABS(paid_amount - (amount - discount_amount)) > 0.005
           OR ABS(profit_amount - (paid_amount - cost_amount)) > 0.005"""),
    ('B09', '退货单头=Σ明细', """
        SELECT COUNT(*) FROM return_order r
        JOIN (SELECT return_id, SUM(refund_amount) a FROM return_order_item GROUP BY return_id) t ON t.return_id = r.id
        WHERE ABS(r.refund_amount - t.a) > 0.005"""),
    ('B10', '退货明细：退款=原单实付×比例、退款单价=原单实付单价、成本=快照×数量', """
        SELECT COUNT(*) FROM return_order_item roi
        JOIN sales_order_item si ON si.id = roi.sales_item_id
        WHERE ABS(roi.refund_amount - ROUND(si.paid_amount * roi.return_qty / si.quantity, 2)) > 0.005
           OR ABS(roi.refund_price - ROUND(si.paid_amount / si.quantity, 2)) > 0.005
           OR ABS(roi.cost_amount - ROUND(roi.return_qty * roi.cost_price, 2)) > 0.005"""),
    ('B11', '退货≤原单实付', """
        SELECT COUNT(*) FROM (
          SELECT sales_order_id, SUM(refund_amount) rf FROM return_order GROUP BY sales_order_id) t
        JOIN sales_order o ON o.id = t.sales_order_id WHERE t.rf > o.paid_amount + 0.005"""),
    ('B12', '已退数量回填一致', """
        SELECT COUNT(*) FROM sales_order_item si
        LEFT JOIN (SELECT sales_item_id, SUM(return_qty) q FROM return_order_item GROUP BY sales_item_id) rr ON rr.sales_item_id = si.id
        WHERE ABS(si.returned_qty - COALESCE(rr.q, 0)) > 0.0005"""),
    ('B13', '退货状态与退货单存在性一致', """
        SELECT COUNT(*) FROM sales_order o
        WHERE (o.return_status = 'NONE') <> (NOT EXISTS (SELECT 1 FROM return_order r WHERE r.sales_order_id = o.id))"""),
    ('B14', '收款记录合计=实付', """
        SELECT COUNT(*) FROM sales_order o
        JOIN (SELECT order_id, SUM(amount) a FROM payment_record GROUP BY order_id) p ON p.order_id = o.id
        WHERE ABS(o.paid_amount - p.a) > 0.005"""),
    ('B15', '有实付必有收款记录', """
        SELECT COUNT(*) FROM sales_order o
        WHERE o.paid_amount > 0 AND NOT EXISTS (SELECT 1 FROM payment_record p WHERE p.order_id = o.id)"""),
    ('B16', '储值/积分非负', "SELECT COUNT(*) FROM member WHERE balance < 0 OR points < 0"),
    ('B17', '储值流水链式连续', """
        SELECT COUNT(*) FROM (
          SELECT change_amount, after_balance,
                 LAG(after_balance) OVER (PARTITION BY member_id ORDER BY id) AS prev
          FROM balance_record) t
        WHERE prev IS NOT NULL AND ABS(prev + change_amount - after_balance) > 0.005"""),
    ('B18', '储值结余=流水末端', """
        SELECT COUNT(*) FROM member m
        JOIN (SELECT b.member_id, b.after_balance FROM balance_record b
              WHERE b.id = (SELECT MAX(x.id) FROM balance_record x WHERE x.member_id = b.member_id)) lb ON lb.member_id = m.id
        WHERE ABS(m.balance - lb.after_balance) > 0.005"""),
    ('B19', '积分流水链式连续', """
        SELECT COUNT(*) FROM (
          SELECT change_value, after_value,
                 LAG(after_value) OVER (PARTITION BY member_id ORDER BY id) AS prev
          FROM point_record) t
        WHERE prev IS NOT NULL AND (prev + change_value) <> after_value"""),
    ('B20', '积分结余=流水末端', """
        SELECT COUNT(*) FROM member m
        JOIN (SELECT member_id, after_value, ROW_NUMBER() OVER (PARTITION BY member_id ORDER BY id DESC) AS rn
              FROM point_record) lp ON lp.member_id = m.id AND lp.rn = 1
        WHERE m.points <> lp.after_value"""),
    ('B21', '会员消费次数/累计消费口径', """
        SELECT COUNT(*) FROM member m
        LEFT JOIN (SELECT member_id, COUNT(*) c, SUM(payable_amount) pa FROM sales_order WHERE member_id IS NOT NULL GROUP BY member_id) so ON so.member_id = m.id
        LEFT JOIN (SELECT member_id, SUM(refund_amount) rf FROM return_order WHERE member_id IS NOT NULL GROUP BY member_id) ro ON ro.member_id = m.id
        WHERE m.consume_times <> COALESCE(so.c, 0)
           OR ABS(m.total_consume - (COALESCE(so.pa, 0) - COALESCE(ro.rf, 0))) > 0.005"""),
    ('B22', '会员等级≥应享等级', """
        SELECT COUNT(*) FROM member m
        JOIN member_level ml ON ml.id = m.level_id
        WHERE ml.threshold < (SELECT MAX(l2.threshold) FROM member_level l2 WHERE l2.threshold <= m.total_consume)"""),
    ('B23', '入库单头=Σ明细', """
        SELECT COUNT(*) FROM receipt_order r
        JOIN (SELECT receipt_id, SUM(amount) a FROM receipt_order_item GROUP BY receipt_id) t ON t.receipt_id = r.id
        WHERE ABS(r.total_amount - t.a) > 0.005"""),
    ('B24', '入库明细金额=数量×单价', """
        SELECT COUNT(*) FROM receipt_order_item
        WHERE ABS(amount - ROUND(received_qty * received_price, 2)) > 0.005"""),
    ('B25', '采购明细实收/退货回填', """
        SELECT COUNT(*) FROM purchase_order_item poi
        LEFT JOIN (SELECT purchase_item_id, SUM(received_qty) q FROM receipt_order_item GROUP BY purchase_item_id) ri ON ri.purchase_item_id = poi.id
        LEFT JOIN (SELECT purchase_item_id, SUM(return_qty) q FROM purchase_return_item GROUP BY purchase_item_id) pt ON pt.purchase_item_id = poi.id
        WHERE ABS(poi.received_qty - COALESCE(ri.q, 0)) > 0.0005
           OR ABS(poi.returned_qty - COALESCE(pt.q, 0)) > 0.0005"""),
    ('B26', '采购单金额=Σ采购明细', """
        SELECT COUNT(*) FROM purchase_order o
        JOIN (SELECT order_id, SUM(amount) a FROM purchase_order_item GROUP BY order_id) t ON t.order_id = o.id
        WHERE ABS(o.total_amount - t.a) > 0.005"""),
    ('B27', '采购退货单头=Σ明细', """
        SELECT COUNT(*) FROM purchase_return_order r
        JOIN (SELECT return_id, SUM(amount) a FROM purchase_return_item GROUP BY return_id) t ON t.return_id = r.id
        WHERE ABS(r.total_amount - t.a) > 0.005"""),
]


def run_check(args, sql):
    proc = subprocess.run(
        ['docker', 'exec', args.container, 'mysql', '-u' + args.user, '-p' + args.password,
         '-N', '-B', args.database, '-e', sql],
        capture_output=True, text=True)
    if proc.returncode != 0:
        return None, proc.stderr.strip().splitlines()[-1] if proc.stderr else 'unknown error'
    lines = [ln for ln in proc.stdout.splitlines() if ln.strip()]
    return int(lines[-1]), None


def main():
    parser = argparse.ArgumentParser(description='SMMS 仿真库一致性核验（27 项 SQL 不变量）')
    parser.add_argument('--container', default='smms-perf-selftest', help='MySQL 容器名')
    parser.add_argument('--user', default='root')
    parser.add_argument('--password', default='testroot')
    parser.add_argument('--database', default='smms')
    args = parser.parse_args()

    total = len(CHECKS)
    bad = 0
    for i, (check_id, title, sql) in enumerate(CHECKS, 1):
        t0 = time.monotonic()
        value, err = run_check(args, sql)
        ms = (time.monotonic() - t0) * 1000
        if err:
            print(f'{check_id}\t{title}\tERROR: {err}\t{ms:.0f}ms', flush=True)
            bad += 1
        else:
            flag = '' if value == 0 else '\t<== VIOLATIONS'
            print(f'{check_id}\t{title}\t{value}\t{ms:.0f}ms{flag}', flush=True)
            if value != 0:
                bad += 1
        print(f'   ({i}/{total} done)', flush=True)
    print(f'BATTERY-DONE bad={bad}/{total}', flush=True)
    sys.exit(1 if bad else 0)


if __name__ == '__main__':
    main()
