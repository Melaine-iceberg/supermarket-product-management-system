/**
 * Element Plus 图标名 → Lucide 图标名映射。
 *
 * 后端菜单表（sys_permission.icon）沿用 Element 图标名，动态菜单与快捷入口
 * 通过 AppIcon 渲染；模板中的静态图标则直接使用 i-lucide-* 名称。
 */
const ICON_MAP: Record<string, string> = {
  AlarmClock: 'alarm-clock',
  ArrowDown: 'chevron-down',
  ArrowLeft: 'arrow-left',
  ArrowRight: 'arrow-right',
  ArrowUp: 'chevron-up',
  Avatar: 'circle-user-round',
  Back: 'arrow-left',
  Bell: 'bell',
  Box: 'box',
  Calendar: 'calendar',
  Check: 'check',
  CircleCheck: 'circle-check',
  CircleCheckFilled: 'circle-check-big',
  CircleClose: 'circle-x',
  CircleCloseFilled: 'circle-x',
  Clock: 'clock',
  Close: 'x',
  Coin: 'coins',
  Collection: 'library-big',
  CopyDocument: 'copy',
  CreditCard: 'credit-card',
  DataAnalysis: 'chart-column',
  DataBoard: 'layout-dashboard',
  DataLine: 'chart-line',
  Delete: 'trash-2',
  Discount: 'percent',
  Document: 'file-text',
  Download: 'download',
  Edit: 'pencil',
  Files: 'files',
  Filter: 'filter',
  Finished: 'circle-check-big',
  FirstAidKit: 'briefcase-medical',
  Folder: 'folder',
  FolderOpened: 'folder-open',
  FullScreen: 'maximize',
  Goods: 'package',
  GoodsFilled: 'package-check',
  Grid: 'grid-3x3',
  Hide: 'eye-off',
  Histogram: 'chart-no-axes-column',
  House: 'house',
  InfoFilled: 'info',
  Key: 'key',
  Link: 'link',
  List: 'list',
  Location: 'map-pin',
  Lock: 'lock',
  MagicStick: 'wand-sparkles',
  Menu: 'menu',
  Message: 'message-square',
  Money: 'banknote',
  Monitor: 'monitor',
  Moon: 'moon',
  More: 'ellipsis',
  Odometer: 'gauge',
  OfficeBuilding: 'building',
  Operation: 'sliders-horizontal',
  Picture: 'image',
  PieChart: 'chart-pie',
  Plus: 'plus',
  Present: 'gift',
  PriceTag: 'tag',
  Printer: 'printer',
  QuestionFilled: 'circle-help',
  Refresh: 'refresh-cw',
  RefreshLeft: 'rotate-ccw',
  Right: 'arrow-right',
  ScaleToOriginal: 'scale',
  Search: 'search',
  Sell: 'tag',
  Setting: 'settings',
  Shop: 'store',
  ShoppingCart: 'shopping-cart',
  Star: 'star',
  Sunny: 'sun',
  SwitchButton: 'log-out',
  Ticket: 'ticket',
  Tickets: 'tickets',
  Timer: 'timer',
  Tools: 'wrench',
  TrendCharts: 'trending-up',
  Trophy: 'trophy',
  Upload: 'upload',
  UploadFilled: 'cloud-upload',
  User: 'user',
  UserFilled: 'user-round',
  Van: 'truck',
  View: 'eye',
  Wallet: 'wallet',
  Warning: 'triangle-alert',
  WarningFilled: 'triangle-alert',
}

function kebabCase(value: string): string {
  return value
    .replace(/([a-z0-9])([A-Z])/g, '$1-$2')
    .replace(/([A-Z]+)([A-Z][a-z])/g, '$1-$2')
    .toLowerCase()
}

export function iconName(name?: string): string | undefined {
  if (!name) return undefined
  if (name.startsWith('i-')) return name
  return `i-lucide-${ICON_MAP[name] ?? kebabCase(name)}`
}

/**
 * 后端菜单/快捷入口图标是运行时动态下发的，构建期扫描不到；
 * 把它们全部加入 Nuxt UI 的 clientBundle，避免运行期请求 api.iconify.design（内网/离线部署必需）。
 */
export const DYNAMIC_ICON_NAMES = Array.from(new Set(Object.values(ICON_MAP))).map(
  (name) => `i-lucide-${name}`,
)
