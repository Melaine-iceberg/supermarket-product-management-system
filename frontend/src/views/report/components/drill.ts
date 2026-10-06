/** 下钻表格列配置（DrillTableDrawer 用）：value 返回展示值，null 显示 "—" */
export interface DrillColumn<T> {
  label: string
  width?: number
  minWidth?: number
  align?: 'left' | 'right' | 'center'
  value: (row: T) => unknown
}
