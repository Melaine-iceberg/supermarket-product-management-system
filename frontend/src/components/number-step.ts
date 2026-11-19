/**
 * UInputNumber 会在失焦时把值吸附到 min + n × step。
 * 金额字段通常只传 precision=2 而未传 step，若默认 step=1，
 * 20.35 会被改成 20.01、100.50 会被改成 100.01。
 *
 * 未显式指定 step 时按 precision 推导：2 位小数 → 0.01，3 位 → 0.001；
 * 无小数位（precision=0 或未指定）则保持整数步长 1。
 */
export function resolveNumberStep(
  step: number | undefined | null,
  precision: number | undefined | null,
): number {
  if (step != null) return step
  if (precision != null && precision > 0) return 10 ** -precision
  return 1
}
