import {
  PAY_TYPE_MAP,
  RETURN_REASON_MAP,
  type ReturnOrderDetailVO,
  type SalesOrderDetailVO,
} from '@/api/sale'
import { pMoney, printSheet, pQty, pTime } from './print'

/**
 * 销售小票与退货凭证（FR-RPT-09、IR-HW-02）：纸宽取 receipt_paper_width 参数。
 * 明细一律取后端落库的成交快照（BR-SALE-04），不用前端购物车数据，保证小票与账一致；
 * 成本与毛利属于内部字段，不出现在顾客可见的小票上（BR-RPT-07）。
 * win 由调用方在点击时同步开好，避免 await 接口后被弹窗拦截。
 */
export function printSalesReceipt(d: SalesOrderDetailVO, win?: Window | null) {
  return printSheet(
    {
      title: '销售小票',
      paper: 'receipt',
      meta: [
        `单号：${d.orderNo}`,
        `收银台：${String(d.counterNo).padStart(2, '0')} 号`,
        `收银员：${d.cashierName}`,
        `时间：${pTime(d.saleTime)}`,
        `会员：${d.memberName ?? '散客'}`,
      ],
      columns: [
        { label: '商品', value: (r) => (r.priceReason ? `${r.goodsName}（改价）` : r.goodsName) },
        { label: '数量', value: (r) => pQty(r.quantity), align: 'right' },
        { label: '单价', value: (r) => pMoney(r.unitPrice), align: 'right' },
        { label: '金额', value: (r) => pMoney(r.amount), align: 'right' },
      ],
      rows: d.items,
      totals: [
        `件数：${d.itemCount}`,
        `商品总额：￥${pMoney(d.goodsAmount)}`,
        `会员优惠：-￥${pMoney(d.memberDiscountAmount)}`,
        `整单优惠：-￥${pMoney(d.orderDiscountAmount)}`,
        `应收：￥${pMoney(d.payableAmount)}`,
        ...d.payments.map((p) => `${PAY_TYPE_MAP[p.payType] ?? p.payType}：￥${pMoney(p.amount)}`),
        `实收：￥${pMoney(d.paidAmount)}`,
        `找零：￥${pMoney(d.changeAmount)}`,
        '谢谢惠顾，退换货请凭此小票办理',
      ],
    },
    win,
  )
}

/** 退货凭证（FR-RPT-09）：退款单价由后端按原成交价计算（BR-SALE-06），凭证只出退款相关字段 */
export function printReturnVoucher(d: ReturnOrderDetailVO, win?: Window | null) {
  const reason = RETURN_REASON_MAP[d.returnReason] ?? d.returnReason
  return printSheet(
    {
      title: '退货凭证',
      paper: 'receipt',
      meta: [
        `退货单号：${d.orderNo}`,
        `原销售单：${d.salesOrderNo}`,
        `退货时间：${pTime(d.returnTime)}`,
        `收银员：${d.cashierName}`,
        `授权人：${d.authorizerName ?? '—'}`,
        `退货原因：${d.reasonRemark ? `${reason}（${d.reasonRemark}）` : reason}`,
        `会员：${d.memberName ?? '散客'}`,
      ],
      columns: [
        { label: '商品', value: (r) => r.goodsName },
        { label: '退货数', value: (r) => pQty(r.returnQty), align: 'right' },
        { label: '退款单价', value: (r) => pMoney(r.refundPrice), align: 'right' },
        { label: '退款金额', value: (r) => pMoney(r.refundAmount), align: 'right' },
      ],
      rows: d.items,
      totals: [
        `退款方式：${PAY_TYPE_MAP[d.refundType] ?? d.refundType}`,
        `退款合计：￥${pMoney(d.refundAmount)}`,
      ],
    },
    win,
  )
}
