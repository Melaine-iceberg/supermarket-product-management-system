import { ElLoading, ElMessage } from 'element-plus'
import { http, saveBlob } from './request'

/** ElLoading 服务实例类型（避免深层路径导入） */
type LoadingInstance = ReturnType<typeof ElLoading.service>

/**
 * 异步任务（重导出/导入提交，2026-10 新增）。
 *
 * 背景：报表导出、大范围清单导出、Excel 导入提交等接口耗时可能超过反向代理的
 * 源站超时（Cloudflare 约 100s），会返回 524。因此这类接口改为：
 *   ① 提交任务 → 立即返回 taskNo（毫秒级响应，不受代理超时影响）
 *   ② 后台线程生成结果文件（进度写入任务记录）
 *   ③ 前端轮询任务状态，完成后自动下载结果文件
 */
export interface AsyncTaskVO {
  taskNo: string
  taskType: 'EXPORT' | 'IMPORT'
  bizType: string
  bizName: string
  status: 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILED'
  progress: number
  phase: string | null
  fileName: string | null
  fileSize: number | null
  errorMessage: string | null
  /** 导入类任务的结果摘要（如 {total, success, failed}），导出类为 null */
  result: unknown
  createTime: string
  finishTime: string | null
  expiresAt: string
}

/** 轮询间隔（毫秒）：任务通常 1~10 秒完成，1.2 秒足够且不打扰后端 */
const POLL_INTERVAL = 1200
/** 轮询上限（约 15 分钟），超时后提示用户稍后在任务中心查看 */
const POLL_TIMEOUT = 15 * 60 * 1000

/** 查询任务状态（仅提交人可见） */
export function getAsyncTask(taskNo: string) {
  return http.get<AsyncTaskVO>(`/tasks/${taskNo}`)
}

/** 提交导出任务（GET 返回任务号，生成动作在后台执行） */
export function submitExportTask(url: string, params?: object) {
  return http.get<AsyncTaskVO>(url, params)
}

/** 提交导入任务（multipart 上传，随后在后台执行） */
export function submitImportTask(url: string, file: File, onProgress?: (percent: number) => void) {
  return http.upload<AsyncTaskVO>(url, file, 'file', onProgress)
}

/** 下载任务结果文件并保存到本地 */
export async function downloadTaskResult(taskNo: string, fileName?: string | null) {
  const blob = await http.download(`/tasks/${taskNo}/download`)
  // 后端业务异常按全局约定以 HTTP 200 + JSON 返回（如 1905 结果已过期），
  // 若直接落盘会得到一个损坏的 xlsx，此处还原为错误提示
  if (blob.type.includes('json')) {
    const message = await readErrorMessage(blob)
    ElMessage.error(message)
    throw new Error(message)
  }
  saveBlob(blob, fileName || 'export.xlsx')
}

/** 解析 JSON 错误响应体中的提示文案 */
async function readErrorMessage(blob: Blob): Promise<string> {
  try {
    const body = JSON.parse(await blob.text())
    if (body && typeof body.message === 'string' && body.message) {
      return body.message
    }
  } catch {
    // 非 JSON 或解析失败时回退为通用提示
  }
  return '下载失败，请稍后重试'
}

function sleep(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** 进度浮层文案：任务名 + 阶段 + 百分比 */
function progressText(task: AsyncTaskVO) {
  const phase = task.phase ? `${task.phase} ` : ''
  const percent = task.progress > 0 ? `${task.progress}%` : ''
  return `${task.bizName}：${phase}${percent}`.trim()
}

/**
 * 等待任务结束（轮询），期间展示进度浮层。
 * 成功返回最终任务；失败抛出错误并已提示。
 */
async function waitTask(task: AsyncTaskVO, loading: LoadingInstance): Promise<AsyncTaskVO> {
  const deadline = Date.now() + POLL_TIMEOUT
  let current = task
  loading.setText(progressText(current))
  while (current.status === 'QUEUED' || current.status === 'RUNNING') {
    if (Date.now() > deadline) {
      loading.close()
      ElMessage.warning(`${current.bizName}仍在后台执行，稍后可在任务结果中下载`)
      throw new Error('TASK_TIMEOUT')
    }
    await sleep(POLL_INTERVAL)
    current = await getAsyncTask(current.taskNo)
    loading.setText(progressText(current))
  }
  if (current.status === 'FAILED') {
    loading.close()
    ElMessage.error(current.errorMessage || `${current.bizName}执行失败`)
    throw new Error(current.errorMessage || 'TASK_FAILED')
  }
  return current
}

/**
 * 导出：提交任务 → 进度浮层 → 完成后返回结果文件 Blob（调用方决定保存文件名）。
 * 用法与原来的同步下载一致，仅多了一层进度提示。
 */
export async function runExportTask(
  url: string,
  params?: object,
  options?: { autoSave?: string },
): Promise<Blob> {
  const task = await submitExportTask(url, params)
  const loading = ElLoading.service({ lock: true, text: `${task.bizName}：正在提交任务…` })
  try {
    const done = await waitTask(task, loading)
    const blob = await http.download(`/tasks/${done.taskNo}/download`)
    if (options?.autoSave) {
      saveBlob(blob, options.autoSave || done.fileName || 'export.xlsx')
    }
    loading.close()
    return blob
  } catch (e) {
    loading.close()
    throw e
  }
}

/**
 * 导入提交：上传文件 → 后台执行 → 返回导入结果对象（失败行明细等）。
 */
export async function runImportTask<T>(
  url: string,
  file: File,
  onProgress?: (percent: number) => void,
): Promise<T> {
  const task = await submitImportTask(url, file, onProgress)
  const loading = ElLoading.service({ lock: true, text: `${task.bizName}：正在提交任务…` })
  try {
    const done = await waitTask(task, loading)
    loading.close()
    ElMessage.success(`${done.bizName}完成`)
    return done.result as T
  } catch (e) {
    loading.close()
    throw e
  }
}
