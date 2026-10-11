import { getPrintInfo, type PrintInfoVO } from '@/api/system'

/**
 * 打印视图（FR-RPT-09、IR-UI-08、IR-HW-02）。
 * 经浏览器打印通道输出：单据类 A4 纵向、明细过宽的报表 A4 横向、小票 58/80 mm。
 * 打印样式与屏幕样式天然隔离（独立窗口），不会出现按钮与导航。
 */
export type Paper = 'A4' | 'receipt'
export type Orientation = 'portrait' | 'landscape'

export interface PrintColumn<T> {
  label: string
  /** 单元格文本；返回 null/undefined 时渲染为 — */
  value: (row: T, index: number) => string | number | null | undefined
  align?: 'left' | 'center' | 'right'
}

export interface PrintSheet<T> {
  /** 单据/报表标题，如「入库验收单」「销售日报」 */
  title: string
  /** 标题下的信息行：单号、统计区间、供应商、操作人等 */
  meta?: (string | null | undefined)[]
  /** 表格主体；与 bodyHtml 二选一 */
  columns?: PrintColumn<T>[]
  rows?: T[]
  /** 自由主体 HTML（调用方须先用 esc() 转义业务文本） */
  bodyHtml?: string
  /** 表格下方合计行（右对齐） */
  totals?: (string | null | undefined)[]
  /** 末尾签署行（横向铺开：验收人 / 授权人 / 打印时间） */
  signs?: (string | null | undefined)[]
  paper?: Paper
  orientation?: Orientation
}

const ESCAPES: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
}

/** 业务文本（商品名/供应商名/备注）写入打印 HTML 前必须转义 */
export function esc(v: string | number | boolean | null | undefined): string {
  if (v == null) return ''
  return String(v).replace(/[&<>"']/g, (c) => ESCAPES[c] as string)
}

/** 打印用金额：空值渲染 — */
export function pMoney(v: number | string | null | undefined): string {
  return v == null || v === '' ? '—' : Number(v).toFixed(2)
}

/** 打印用数量：去掉无意义的小数尾零 */
export function pQty(v: number | string | null | undefined): string {
  if (v == null || v === '') return '—'
  const n = Number(v)
  return Number.isInteger(n) ? String(n) : n.toFixed(2)
}

/** 打印用百分比：净销售为 0 时后端返回 null，渲染 — 而非 0%（BR-RPT-01） */
export function pPercent(v: number | string | null | undefined): string {
  return v == null || v === '' ? '—' : `${Number(v).toFixed(2)}%`
}

export function pText(v: string | number | boolean | null | undefined): string {
  return v == null || v === '' ? '—' : String(v)
}

export function pTime(v: string | null | undefined): string {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}

/** 打印页眉参数进程内缓存：店名/地址/电话/纸宽改动频率极低，无需每次打印都请求 */
let infoPromise: Promise<PrintInfoVO> | null = null

function loadPrintInfo(): Promise<PrintInfoVO | null> {
  infoPromise ??= getPrintInfo()
  return infoPromise.catch((e) => {
    infoPromise = null
    throw e
  })
}

function nowText(): string {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/** @page 尺寸：小票按参数纸宽出票，单据/报表按 A4 纵向或横向（IR-UI-08） */
function pageCss(paper: Paper, orientation: Orientation, paperWidth: number | undefined): string {
  if (paper === 'receipt') {
    return `@page { size: ${paperWidth === 80 ? '80mm auto' : '58mm auto'}; margin: 2mm; }`
  }
  return `@page { size: A4 ${orientation}; margin: ${orientation === 'landscape' ? '12mm' : '14mm'}; }`
}

function baseCss(paper: Paper, widthMm: number): string {
  const receipt = paper === 'receipt'
  return `
    * { box-sizing: border-box; }
    html, body { margin: 0; padding: 0; background: #fff; color: #000; }
    body {
      font-family: "SimSun", "Songti SC", "Microsoft YaHei", serif;
      font-size: ${receipt ? '11px' : '13px'};
      -webkit-print-color-adjust: exact;
      print-color-adjust: exact;
      ${receipt ? `width: ${widthMm}mm; margin: 0 auto;` : ''}
    }
    .store { text-align: center; margin-bottom: 6px; }
    .store .name { font-size: ${receipt ? '15px' : '18px'}; font-weight: 700; letter-spacing: 2px; }
    .store .line { font-size: ${receipt ? '10px' : '11px'}; }
    h1 {
      text-align: center; font-weight: 700; margin: 8px 0 6px;
      font-size: ${receipt ? '13px' : '17px'}; letter-spacing: ${receipt ? '2px' : '6px'};
    }
    .meta { font-size: ${receipt ? '10px' : '12px'}; margin-bottom: 8px; }
    .meta div { display: flex; justify-content: space-between; gap: 12px; }
    .dash { border: none; border-top: 1px dashed #000; margin: 6px 0; }
    table { width: 100%; border-collapse: collapse; font-size: ${receipt ? '10px' : '12px'}; }
    th, td { padding: ${receipt ? '1px 2px' : '4px 6px'}; vertical-align: top; word-break: break-all; }
    ${receipt ? '' : 'th, td { border: 1px solid #000; }'}
    thead { display: table-header-group; }
    tfoot { display: table-footer-group; }
    tr { break-inside: avoid; }
    .l { text-align: left; } .c { text-align: center; } .r { text-align: right; }
    .sec { font-weight: 700; font-size: ${receipt ? '12px' : '14px'}; margin: 14px 0 6px; }
    .sec:first-child { margin-top: 0; }
    .totals { margin-top: 8px; font-size: ${receipt ? '11px' : '13px'}; }
    .totals div { display: flex; justify-content: flex-end; gap: 6px; }
    .signs { margin-top: 22px; display: flex; flex-wrap: wrap; gap: 8px 24px; justify-content: space-between; font-size: ${receipt ? '10px' : '12px'}; }
    .foot { margin-top: 14px; text-align: center; font-size: ${receipt ? '9px' : '11px'}; }
  `
}

/**
 * 无权字段由服务端置空（BR-RPT-07）：整列都是 — 时不打印该列，
 * 避免收银员的报表上出现一排空的成本/毛利列。
 */
export function dropEmptyColumns<T>(columns: PrintColumn<T>[], rows: T[]): PrintColumn<T>[] {
  if (!rows.length) return columns
  return columns.filter((c) =>
    rows.some((row, i) => {
      const v = c.value(row, i)
      return v != null && v !== '' && v !== '—'
    }),
  )
}

/**
 * bodyHtml 用的简单表格：多段主体（看板 KPI + 待办 + 趋势 + TOP10）各自成表时使用。
 * 单元格一律转义，调用方直接传业务文本即可。
 */
export function pTable(
  headers: string[],
  rows: (string | number | null | undefined)[][],
  aligns: ('left' | 'center' | 'right')[] = [],
): string {
  const cls = (i: number) => (aligns[i] === 'right' ? 'r' : aligns[i] === 'center' ? 'c' : 'l')
  const head = headers.map((h, i) => `<th class="${cls(i)}">${esc(h)}</th>`).join('')
  const body = rows
    .map((cells) => `<tr>${cells.map((c, i) => `<td class="${cls(i)}">${esc(c == null || c === '' ? '—' : c)}</td>`).join('')}</tr>`)
    .join('')
  return `<table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>`
}

function tableHtml<T>(columns: PrintColumn<T>[], rows: T[]): string {
  const head = columns.map((c) => `<th class="${c.align === 'right' ? 'r' : c.align === 'center' ? 'c' : 'l'}">${esc(c.label)}</th>`)
  const body = rows
    .map((row, i) => {
      const cells = columns
        .map((c) => {
          const text = c.value(row, i)
          return `<td class="${c.align === 'right' ? 'r' : c.align === 'center' ? 'c' : 'l'}">${esc(text == null || text === '' ? '—' : text)}</td>`
        })
        .join('')
      return `<tr>${cells}</tr>`
    })
    .join('')
  return `<table><thead><tr>${head.join('')}</tr></thead><tbody>${body}</tbody></table>`
}

/**
 * 打开打印窗口。必须在用户点击的同步调用栈内调用：一旦中间 await 了接口，
 * window.open 就脱离用户手势，会被浏览器当作弹窗拦截。
 * 需要先拉数据再打印的单据，先调本函数拿到窗口句柄，再传给 printSheet。
 */
export function openPrintWindow(paper: Paper = 'A4'): Window | null {
  const win = window.open('', '_blank', paper === 'receipt' ? 'width=420,height=640' : 'width=960,height=720')
  if (!win) {
    ElMessage.warning('浏览器拦截了打印窗口，请允许弹出窗口后重试')
    return null
  }
  win.document.write(
    '<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8"></head>' +
      '<body style="font:14px sans-serif;padding:24px;color:#666">正在生成打印视图…</body></html>',
  )
  return win
}

/**
 * 输出打印视图并触发浏览器打印。
 * 页眉取系统参数中的超市名称/地址/电话；页脚固定输出生成时间（FR-RPT-09）。
 * win 为空时当场开窗，仅适用于调用方无需 await 即可给出数据的场景。
 */
export async function printSheet<T>(sheet: PrintSheet<T>, win?: Window | null): Promise<void> {
  const paper = sheet.paper ?? 'A4'
  const orientation = sheet.orientation ?? 'portrait'
  const target = win ?? openPrintWindow(paper)
  if (!target) return

  // 页眉参数读取失败不阻断打印：单据主体比店名更重要
  const info = await loadPrintInfo().catch(() => null)
  const css = pageCss(paper, orientation, info?.receiptPaperWidth)
  const bodyWidth = paper === 'receipt' ? (info?.receiptPaperWidth === 80 ? 76 : 54) : 0
  const storeName = info?.storeName || '超市'
  const storeLines = [info?.storeAddress, info?.storePhone].filter((v): v is string => !!v)
  const meta = (sheet.meta ?? []).filter((v): v is string => !!v)
  const generatedAt = nowText()

  const body =
    sheet.columns && sheet.rows ? tableHtml(sheet.columns, sheet.rows) : (sheet.bodyHtml ?? '')

  const html = `<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
<title>${esc(sheet.title)}</title><style>${css}${baseCss(paper, bodyWidth)}</style></head>
<body>
  <div class="store">
    <div class="name">${esc(storeName)}</div>
    ${storeLines.map((l) => `<div class="line">${esc(l)}</div>`).join('')}
  </div>
  <h1>${esc(sheet.title)}</h1>
  ${paper === 'receipt' ? '<hr class="dash">' : ''}
  ${meta.length ? `<div class="meta">${meta.map((m) => `<div><span>${esc(m)}</span></div>`).join('')}</div>` : ''}
  ${body}
  ${sheet.totals?.length ? `<div class="totals">${sheet.totals.filter((v): v is string => !!v).map((t) => `<div><span>${esc(t)}</span></div>`).join('')}</div>` : ''}
  ${paper === 'receipt' ? '<hr class="dash">' : ''}
  ${sheet.signs?.length ? `<div class="signs">${sheet.signs.filter((v): v is string => !!v).map((s) => `<span>${esc(s)}</span>`).join('')}</div>` : ''}
  <div class="foot">${esc(storeName)} · 生成时间 ${generatedAt}</div>
</body></html>`

  target.document.open()
  target.document.write(html)
  target.document.close()
  target.focus()
  target.print()
}
