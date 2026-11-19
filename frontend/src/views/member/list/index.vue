<script setup lang="ts">
import { notify } from '@/utils/notify'
import { confirm } from '@/utils/confirm'
import { moneyText } from '@/utils/format'
import { computed, onMounted, reactive, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { saveBlob } from '@/api/request'
import { useUnsavedGuard } from '@/utils/unsavedGuard'
import {
  BALANCE_CHANGE_MAP,
  POINT_CHANGE_MAP,
  RECHARGE_PAY_MAP,
  commitMemberImport,
  createMember,
  downloadMemberTemplate,
  getBalanceRecords,
  getConsumeRecords,
  getMemberDetail,
  getPointRecords,
  pageMembers,
  rechargeMember,
  updateMember,
  updateMemberStatus,
  validateMemberImport,
  type BalanceRecordVO,
  type ConsumeResult,
  type MemberListVO,
  type PointRecordVO,
  type RechargePayType,
} from '@/api/member'
import { listLevels } from '@/api/memberLevel'

const auth = useAuthStore()

function fmtTime(v: string | null | undefined) {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}

function money(v: number | null | undefined) {
  return v == null ? '—' : moneyText(v, 2)
}

/** 旧标签类型 → UBadge 颜色（info 显示为中性灰，保持原有视觉） */
type BadgeColor = 'primary' | 'secondary' | 'success' | 'info' | 'warning' | 'error' | 'neutral'

function tagColor(type?: string): BadgeColor {
  if (!type || type === 'info') return 'neutral'
  if (type === 'danger') return 'error'
  if (
    type === 'primary' ||
    type === 'secondary' ||
    type === 'success' ||
    type === 'warning' ||
    type === 'error' ||
    type === 'neutral'
  ) {
    return type
  }
  return 'neutral'
}

// ---------- 查询（FR-MEM-03） ----------
const loading = ref(false)
const list = ref<MemberListVO[]>([])
const total = ref(0)
const query = reactive({
  memberNo: '',
  name: '',
  phone: '',
  levelId: null as number | null,
  status: null as number | null,
  dateRange: [] as string[],
  page: 1,
  size: 20,
})

/** 等级筛选项：等级接口需 sys:mem-level:list 权限（收银员仅查询/注册/充值） */
const levelOptions = ref<{ id: number; name: string }[]>([])
const canListLevels = computed(() => auth.hasPerm('sys:mem-level:list'))

async function load() {
  loading.value = true
  try {
    const data = await pageMembers({
      memberNo: query.memberNo.trim() || undefined,
      name: query.name.trim() || undefined,
      phone: query.phone.trim() || undefined,
      levelId: query.levelId ?? undefined,
      status: query.status ?? undefined,
      beginDate: query.dateRange?.[0],
      endDate: query.dateRange?.[1],
      page: query.page,
      size: query.size,
    })
    list.value = data.list
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 1
  load()
}

function resetQuery() {
  Object.assign(query, {
    memberNo: '',
    name: '',
    phone: '',
    levelId: null,
    status: null,
    dateRange: [],
    page: 1,
  })
  load()
}

async function loadLevels() {
  if (!canListLevels.value) return
  levelOptions.value = await listLevels()
}

onMounted(() => {
  load()
  loadLevels()
})

// ---------- 注册 / 编辑（FR-MEM-01/02） ----------
const editDialog = reactive({
  visible: false,
  saving: false,
  editing: false,
  id: 0,
  form: {
    name: '',
    phone: '',
    gender: undefined as number | undefined,
    birthday: null as string | null,
    address: '',
    remark: '',
  },
})

/** 表单快照：用于未保存离开提醒（IR-UI-07/GAP-P2-25） */
let editSnapshot = ''

function editDirty() {
  return editDialog.visible && JSON.stringify(editDialog.form) !== editSnapshot
}

useUnsavedGuard(editDirty, '会员表单尚未保存，离开将丢失当前修改，确认离开？')

function openCreate() {
  editDialog.editing = false
  editDialog.id = 0
  editDialog.form = { name: '', phone: '', gender: undefined, birthday: null, address: '', remark: '' }
  editDialog.visible = true
  editSnapshot = JSON.stringify(editDialog.form)
}

async function openEdit(row: MemberListVO) {
  // 以详情接口取最新档案（列表行数据可能滞后）
  const detail = await getMemberDetail(row.id)
  editDialog.editing = true
  editDialog.id = row.id
  editDialog.form = {
    name: detail.name,
    phone: detail.phone,
    gender: detail.gender ?? undefined,
    birthday: detail.birthday,
    address: detail.address ?? '',
    remark: detail.remark ?? '',
  }
  editDialog.visible = true
  editSnapshot = JSON.stringify(editDialog.form)
}

/** 手机号被脱敏时禁改（无完整手机号查看权限的角色，BR-MEM-08） */
const phoneDisabled = computed(() => editDialog.editing && editDialog.form.phone.includes('*'))

async function saveMember() {
  const form = editDialog.form
  if (!form.name.trim()) {
    notify.warning('请输入姓名')
    return
  }
  if (!/^1[3-9]\d{9}$/.test(form.phone)) {
    notify.warning('手机号须为大陆 11 位手机号（BR-MEM-01）')
    return
  }
  editDialog.saving = true
  try {
    const payload = {
      name: form.name.trim(),
      phone: form.phone,
      gender: form.gender ?? undefined,
      birthday: form.birthday ?? undefined,
      address: form.address.trim() || undefined,
      remark: form.remark.trim() || undefined,
    }
    if (editDialog.editing) {
      await updateMember(editDialog.id, payload)
      notify.success('会员档案已更新')
    } else {
      const created = await createMember(payload)
      notify.success(`注册成功，会员编号 ${created.memberNo}`)
    }
    editDialog.visible = false
    load()
  } finally {
    editDialog.saving = false
  }
}

// ---------- 停用 / 启用（BR-MEM-03） ----------
async function toggleStatus(row: MemberListVO) {
  const to = row.status === 1 ? 0 : 1
  if (to === 0) {
    await confirm(
      `停用后【${row.name}】不可再被收银台识别，不享受折扣与积分；历史数据保留。确认停用？`,
      '停用会员',
      { type: 'warning' },
    )
  }
  await updateMemberStatus(row.id, to)
  notify.success(to === 1 ? '已启用' : '已停用')
  load()
}

// ---------- 详情抽屉（FR-MEM-03/04/07/08） ----------
const drawer = reactive({
  visible: false,
  loading: false,
  member: null as MemberListVO | null,
  tab: 'consume',
})

const consumeState = reactive({
  loading: false,
  dateRange: [] as string[],
  page: 1,
  size: 10,
  total: 0,
  list: [] as ConsumeResult['records']['list'],
  stat: { totalAmount: 0, orderCount: 0, avgAmount: 0 },
})

const pointsState = reactive({
  loading: false,
  page: 1,
  size: 10,
  total: 0,
  list: [] as PointRecordVO[],
})

const balanceState = reactive({
  loading: false,
  page: 1,
  size: 10,
  total: 0,
  list: [] as BalanceRecordVO[],
})

async function openDetail(row: MemberListVO) {
  drawer.visible = true
  drawer.loading = true
  drawer.tab = 'consume'
  try {
    drawer.member = await getMemberDetail(row.id)
    consumeState.page = 1
    consumeState.dateRange = []
    pointsState.page = 1
    balanceState.page = 1
    await Promise.all([loadConsume(), loadPoints(), loadBalances()])
  } finally {
    drawer.loading = false
  }
}

async function loadConsume() {
  if (!drawer.member) return
  consumeState.loading = true
  try {
    const result = await getConsumeRecords(drawer.member.id, {
      beginDate: consumeState.dateRange?.[0],
      endDate: consumeState.dateRange?.[1],
      page: consumeState.page,
      size: consumeState.size,
    })
    consumeState.list = result.records.list
    consumeState.total = result.records.total
    consumeState.stat = result.stat
  } finally {
    consumeState.loading = false
  }
}

function searchConsume() {
  consumeState.page = 1
  loadConsume()
}

async function loadPoints() {
  if (!drawer.member) return
  pointsState.loading = true
  try {
    const data = await getPointRecords(drawer.member.id, pointsState.page, pointsState.size)
    pointsState.list = data.list
    pointsState.total = data.total
  } finally {
    pointsState.loading = false
  }
}

async function loadBalances() {
  if (!drawer.member) return
  balanceState.loading = true
  try {
    const data = await getBalanceRecords(drawer.member.id, balanceState.page, balanceState.size)
    balanceState.list = data.list
    balanceState.total = data.total
  } finally {
    balanceState.loading = false
  }
}

function onDrawerTab(name: string | number) {
  if (name === 'points' && pointsState.list.length === 0) loadPoints()
  if (name === 'balance' && balanceState.list.length === 0) loadBalances()
}

// ---------- 储值充值（FR-MEM-08，店长授权） ----------
const rechargeDialog = reactive({
  visible: false,
  saving: false,
  member: null as MemberListVO | null,
  form: {
    amount: null as number | null,
    payType: 'CASH' as RechargePayType,
    authorizerUsername: '',
    authorizerPassword: '',
    remark: '',
  },
})

function openRecharge(row: MemberListVO) {
  rechargeDialog.member = row
  rechargeDialog.form = {
    amount: null,
    payType: 'CASH' as RechargePayType,
    authorizerUsername: '',
    authorizerPassword: '',
    remark: '',
  }
  rechargeDialog.visible = true
}

async function saveRecharge() {
  const form = rechargeDialog.form
  if (form.amount == null || form.amount <= 0) {
    notify.warning('充值金额必须大于 0')
    return
  }
  if (!form.authorizerUsername.trim()) {
    notify.warning('储值充值须店长授权，请填写授权店长账号（FR-MEM-08）')
    return
  }
  if (form.authorizerUsername.trim() === auth.user?.username) {
    notify.warning('授权人不得为发起人本人')
    return
  }
  if (!form.authorizerPassword) {
    notify.warning('请填写授权店长密码以完成授权（BR-SYS-06）')
    return
  }
  rechargeDialog.saving = true
  try {
    const balance = await rechargeMember(rechargeDialog.member!.id, {
      amount: form.amount,
      payType: form.payType,
      authorizerUsername: form.authorizerUsername.trim(),
      authorizerPassword: form.authorizerPassword,
      remark: form.remark.trim() || undefined,
    })
    notify.success(`充值成功，当前余额 ￥${moneyText(balance, 2)}`)
    rechargeDialog.visible = false
    load()
    // 抽屉打开中时同步刷新档案与流水
    if (drawer.visible && drawer.member?.id === rechargeDialog.member!.id) {
      drawer.member = await getMemberDetail(drawer.member.id)
      balanceState.page = 1
      loadBalances()
    }
  } finally {
    rechargeDialog.saving = false
  }
}

// ---------- Excel 批量导入（FR-MEM-03：校验—确认—部分提交） ----------
const importDialog = reactive({
  visible: false,
  step: 'upload' as 'upload' | 'report' | 'done',
  file: null as File | null,
  validating: false,
  committing: false,
  report: null as import('@/api/member').MemberImportReportVO | null,
  result: null as import('@/api/member').MemberImportResultVO | null,
})

function openImport() {
  importDialog.step = 'upload'
  importDialog.file = null
  importDialog.report = null
  importDialog.result = null
  importDialog.visible = true
}

async function onImportFileChange(file: { raw?: File }) {
  importDialog.file = file.raw ?? null
}

/** 文件上传已改为隐藏 file input：选文件 / 拖拽 / 移除 均复用上面的导入状态 */
const importFileInputRef = ref<HTMLInputElement>()

function pickImportFile() {
  importFileInputRef.value?.click()
}

function onImportFileInput(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (file) void onImportFileChange({ raw: file })
  input.value = ''
}

function onImportDrop(event: DragEvent) {
  const file = event.dataTransfer?.files?.[0]
  if (file) void onImportFileChange({ raw: file })
}

function clearImportFile() {
  importDialog.file = null
  if (importFileInputRef.value) importFileInputRef.value.value = ''
}

async function doValidate() {
  if (!importDialog.file) {
    notify.warning('请先选择导入文件')
    return
  }
  importDialog.validating = true
  try {
    importDialog.report = await validateMemberImport(importDialog.file)
    importDialog.step = 'report'
  } finally {
    importDialog.validating = false
  }
}

async function doCommit() {
  if (!importDialog.file) return
  importDialog.committing = true
  try {
    importDialog.result = await commitMemberImport(importDialog.file)
    importDialog.step = 'done'
    notify.success(`成功导入 ${importDialog.result.imported} 位会员`)
    load()
  } finally {
    importDialog.committing = false
  }
}

async function doDownloadTemplate() {
  saveBlob(await downloadMemberTemplate(), 'member-import-template.xlsx')
}
</script>

<template>
  <div class="page">
    <!-- 筛选区（FR-MEM-03） -->
    <UCard class="search-card">
      <AppForm inline @submit.prevent>
        <AppFormItem label="会员编号">
          <AppInput v-model="query.memberNo" placeholder="M+8位流水" clearable @keyup.enter="search" />
        </AppFormItem>
        <AppFormItem label="姓名">
          <AppInput v-model="query.name" placeholder="姓名模糊" clearable @keyup.enter="search" />
        </AppFormItem>
        <AppFormItem label="手机号">
          <AppInput v-model="query.phone" placeholder="11位手机号" clearable @keyup.enter="search" />
        </AppFormItem>
        <AppFormItem v-if="canListLevels" label="等级">
          <AppSelect v-model="query.levelId" clearable placeholder="全部" style="width: 120px">
            <AppOption v-for="l in levelOptions" :key="l.id" :label="l.name" :value="l.id" />
          </AppSelect>
        </AppFormItem>
        <AppFormItem label="状态">
          <AppSelect v-model="query.status" clearable placeholder="全部" style="width: 100px">
            <AppOption label="正常" :value="1" />
            <AppOption label="停用" :value="0" />
          </AppSelect>
        </AppFormItem>
        <AppFormItem label="注册日期">
          <AppDatePicker
            v-model="query.dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始"
            end-placeholder="结束"
            style="width: 240px"
          />
        </AppFormItem>
        <AppFormItem>
          <div class="flex items-center gap-2">
            <UButton label="查询" @click="search" />
            <UButton color="neutral" variant="outline" label="重置" @click="resetQuery" />
          </div>
        </AppFormItem>
      </AppForm>
    </UCard>

    <UCard>
      <template #header>
        <div class="card-head">
          <span>会员列表（{{ total }}）</span>
          <div class="flex items-center gap-2">
            <UButton
              v-if="auth.hasPerm('sys:mem:import')"
              color="neutral"
              variant="outline"
              label="批量导入"
              @click="openImport"
            />
            <UButton v-if="auth.hasPerm('sys:mem:create')" label="注册会员" @click="openCreate" />
          </div>
        </div>
      </template>

      <AppTable :loading="loading" border :data="list" stripe>
        <AppTableColumn prop="memberNo" label="会员编号" width="120" />
        <AppTableColumn prop="name" label="姓名" width="100" show-overflow-tooltip />
        <AppTableColumn prop="phone" label="手机号" width="130" />
        <AppTableColumn prop="levelName" label="等级" width="100">
          <template #default="{ row }">{{ row.levelName || '—' }}</template>
        </AppTableColumn>
        <AppTableColumn prop="points" label="积分" width="90" align="right" />
        <AppTableColumn label="储值余额" width="100" align="right">
          <template #default="{ row }">￥{{ money(row.balance) }}</template>
        </AppTableColumn>
        <AppTableColumn label="累计消费" width="100" align="right">
          <template #default="{ row }">￥{{ money(row.totalConsume) }}</template>
        </AppTableColumn>
        <AppTableColumn prop="consumeTimes" label="消费次数" width="90" align="right" />
        <AppTableColumn label="最近消费" width="150">
          <template #default="{ row }">{{ fmtTime(row.lastConsumeTime) }}</template>
        </AppTableColumn>
        <AppTableColumn label="状态" width="80" align="center">
          <template #default="{ row }">
            <UBadge :color="row.status === 1 ? 'success' : 'error'" variant="soft" size="sm">
              {{ row.status === 1 ? '正常' : '停用' }}
            </UBadge>
          </template>
        </AppTableColumn>
        <AppTableColumn label="操作" width="230" fixed="right" align="center">
          <template #default="{ row }">
            <UButton variant="link" size="sm" label="详情" @click="openDetail(row as MemberListVO)" />
            <UButton
              v-if="auth.hasPerm('sys:mem:update')"
              variant="link"
              size="sm"
              label="编辑"
              @click="openEdit(row as MemberListVO)"
            />
            <UButton
              v-if="auth.hasPerm('sys:mem:recharge') && row.status === 1"
              variant="link"
              size="sm"
              color="warning"
              label="充值"
              @click="openRecharge(row as MemberListVO)"
            />
            <UButton
              v-if="auth.hasPerm('sys:mem:status')"
              variant="link"
              size="sm"
              :color="row.status === 1 ? 'error' : 'success'"
              :label="row.status === 1 ? '停用' : '启用'"
              @click="toggleStatus(row as MemberListVO)"
            />
          </template>
        </AppTableColumn>
      </AppTable>

      <div class="pagination-row">
        <AppPagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="load"
          @size-change="search"
        />
      </div>
    </UCard>

    <!-- 注册 / 编辑（FR-MEM-01/02） -->
    <AppModal
      v-model="editDialog.visible"
      :title="editDialog.editing ? '编辑会员' : '注册会员'"
      width="480px"
      :close-on-click-modal="false"
    >
      <UAlert
        v-if="editDialog.editing"
        color="info"
        icon="i-lucide-info"
        title="积分、累计消费额、储值余额不可手工修改，只能通过业务单据产生（FR-MEM-02）"
        class="dialog-alert"
      />
      <AppForm label-width="80px" @submit.prevent>
        <AppFormItem label="姓名" required>
          <AppInput v-model="editDialog.form.name" maxlength="30" placeholder="会员姓名" />
        </AppFormItem>
        <AppFormItem label="手机号" required>
          <AppInput
            v-model="editDialog.form.phone"
            :disabled="phoneDisabled"
            maxlength="11"
            placeholder="大陆 11 位手机号（唯一识别键）"
          />
          <div v-if="phoneDisabled" class="form-tip">
            手机号已按角色权限脱敏展示（BR-MEM-08），如需修改请联系店长
          </div>
        </AppFormItem>
        <AppFormItem label="性别" label-as="span">
          <AppRadioGroup v-model="editDialog.form.gender">
            <AppRadio :value="1">男</AppRadio>
            <AppRadio :value="2">女</AppRadio>
          </AppRadioGroup>
        </AppFormItem>
        <AppFormItem label="生日">
          <AppDatePicker
            v-model="editDialog.form.birthday"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选填（生日提醒用）"
            style="width: 100%"
          />
        </AppFormItem>
        <AppFormItem label="地址">
          <AppInput v-model="editDialog.form.address" maxlength="200" placeholder="选填" />
        </AppFormItem>
        <AppFormItem label="备注">
          <AppInput v-model="editDialog.form.remark" type="textarea" :rows="2" maxlength="200" placeholder="选填" />
        </AppFormItem>
      </AppForm>
      <template #footer>
        <UButton color="neutral" variant="outline" label="取消" @click="editDialog.visible = false" />
        <UButton :loading="editDialog.saving" label="保存" @click="saveMember" />
      </template>
    </AppModal>

    <!-- 储值充值（FR-MEM-08） -->
    <AppModal
      v-model="rechargeDialog.visible"
      :title="`储值充值：${rechargeDialog.member?.name ?? ''}（余额 ￥${money(rechargeDialog.member?.balance)}）`"
      width="440px"
      :close-on-click-modal="false"
    >
      <UAlert
        color="info"
        icon="i-lucide-info"
        title="充值须店长授权（输入店长账号+密码，授权人 ≠ 发起人）；充值不计积分，余额变动全部写入不可删改流水"
        class="dialog-alert"
      />
      <AppForm label-width="80px" @submit.prevent>
        <AppFormItem label="充值金额" required>
          <AppInputNumber
            v-model="rechargeDialog.form.amount"
            :min="0.01"
            :precision="2"
            :controls="false"
            placeholder="元"
            style="width: 100%"
          />
        </AppFormItem>
        <AppFormItem label="收款方式" required label-as="span">
          <AppRadioGroup v-model="rechargeDialog.form.payType">
            <AppRadio v-for="(label, type) in RECHARGE_PAY_MAP" :key="type" :value="type">{{ label }}</AppRadio>
          </AppRadioGroup>
        </AppFormItem>
        <AppFormItem label="授权账号" required>
          <AppInput v-model="rechargeDialog.form.authorizerUsername" maxlength="30" placeholder="店长登录账号" />
        </AppFormItem>
        <AppFormItem label="授权密码" required>
          <AppInput
            v-model="rechargeDialog.form.authorizerPassword"
            type="password"
            show-password
            maxlength="50"
            placeholder="店长账号密码（现场授权，BR-SYS-06）"
          />
        </AppFormItem>
        <AppFormItem label="备注">
          <AppInput v-model="rechargeDialog.form.remark" maxlength="200" placeholder="选填" />
        </AppFormItem>
      </AppForm>
      <template #footer>
        <UButton color="neutral" variant="outline" label="取消" @click="rechargeDialog.visible = false" />
        <UButton :loading="rechargeDialog.saving" label="确认充值" @click="saveRecharge" />
      </template>
    </AppModal>

    <!-- 详情抽屉（FR-MEM-03/04/07/08） -->
    <AppDrawer v-model="drawer.visible" size="720px" :title="`会员详情：${drawer.member?.name ?? ''}`">
      <div v-loading="drawer.loading">
        <template v-if="drawer.member">
          <div class="profile-card">
            <div class="profile-head">
              <div class="flex items-center gap-2">
                <span class="profile-name">{{ drawer.member.name }}</span>
                <UBadge variant="soft" size="sm">{{ drawer.member.levelName || '普通会员' }}</UBadge>
                <UBadge :color="drawer.member.status === 1 ? 'success' : 'error'" variant="soft" size="sm">
                  {{ drawer.member.status === 1 ? '正常' : '停用' }}
                </UBadge>
              </div>
              <UButton
                v-if="auth.hasPerm('sys:mem:recharge') && drawer.member.status === 1"
                color="warning"
                size="sm"
                label="储值充值"
                @click="openRecharge(drawer.member)"
              />
            </div>
            <AppDescriptions :column="3" size="small" border class="profile-desc">
              <AppDescriptionsItem label="会员编号">{{ drawer.member.memberNo }}</AppDescriptionsItem>
              <AppDescriptionsItem label="手机号">{{ drawer.member.phone }}</AppDescriptionsItem>
              <AppDescriptionsItem label="当前积分">{{ drawer.member.points }}</AppDescriptionsItem>
              <AppDescriptionsItem label="储值余额">￥{{ money(drawer.member.balance) }}</AppDescriptionsItem>
              <AppDescriptionsItem label="累计消费">￥{{ money(drawer.member.totalConsume) }}</AppDescriptionsItem>
              <AppDescriptionsItem label="消费次数">{{ drawer.member.consumeTimes }}</AppDescriptionsItem>
              <AppDescriptionsItem label="性别">
                {{ drawer.member.gender === 1 ? '男' : drawer.member.gender === 2 ? '女' : '—' }}
              </AppDescriptionsItem>
              <AppDescriptionsItem label="生日">{{ drawer.member.birthday ?? '—' }}</AppDescriptionsItem>
              <AppDescriptionsItem label="最近消费">{{ fmtTime(drawer.member.lastConsumeTime) }}</AppDescriptionsItem>
              <AppDescriptionsItem label="注册人">{{ drawer.member.registerName ?? '—' }}</AppDescriptionsItem>
              <AppDescriptionsItem label="注册时间">{{ fmtTime(drawer.member.registerTime) }}</AppDescriptionsItem>
              <AppDescriptionsItem label="地址">{{ drawer.member.address || '—' }}</AppDescriptionsItem>
              <AppDescriptionsItem label="备注" :span="3">{{ drawer.member.remark || '—' }}</AppDescriptionsItem>
            </AppDescriptions>
          </div>

          <AppTabs v-model="drawer.tab" @tab-change="onDrawerTab">
            <!-- 消费记录（FR-MEM-04） -->
            <AppTabPane label="消费记录" name="consume">
              <div class="stat-bar">
                <AppDatePicker
                  v-model="consumeState.dateRange"
                  type="daterange"
                  value-format="YYYY-MM-DD"
                  start-placeholder="统计开始"
                  end-placeholder="统计结束"
                  style="width: 260px"
                  @change="searchConsume"
                />
                <span>消费总额 <b>￥{{ money(consumeState.stat.totalAmount) }}</b></span>
                <span>频次 <b>{{ consumeState.stat.orderCount }}</b> 单</span>
                <span>客单价 <b>￥{{ money(consumeState.stat.avgAmount) }}</b></span>
              </div>
              <AppTable :loading="consumeState.loading" :data="consumeState.list" size="small">
                <AppTableColumn label="类型" width="70" align="center">
                  <template #default="{ row }">
                    <UBadge :color="row.type === 'SALE' ? 'primary' : 'warning'" variant="soft" size="sm">
                      {{ row.type === 'SALE' ? '销售' : '退货' }}
                    </UBadge>
                  </template>
                </AppTableColumn>
                <AppTableColumn prop="orderNo" label="单号" width="160" />
                <AppTableColumn label="时间" width="150">
                  <template #default="{ row }">{{ fmtTime(row.time) }}</template>
                </AppTableColumn>
                <AppTableColumn label="商品总额" width="95" align="right">
                  <template #default="{ row }">￥{{ money(row.goodsAmount) }}</template>
                </AppTableColumn>
                <AppTableColumn label="优惠" width="85" align="right">
                  <template #default="{ row }">￥{{ money(row.discountAmount) }}</template>
                </AppTableColumn>
                <AppTableColumn label="实付/退款" width="95" align="right">
                  <template #default="{ row }">
                    <span :class="row.type === 'RETURN' ? 'refund' : ''">￥{{ money(row.amount) }}</span>
                  </template>
                </AppTableColumn>
                <AppTableColumn label="积分变动" width="85" align="right">
                  <template #default="{ row }">
                    <span v-if="row.pointsChange != null" :class="row.pointsChange >= 0 ? 'plus' : 'minus'">
                      {{ row.pointsChange >= 0 ? '+' : '' }}{{ row.pointsChange }}
                    </span>
                    <span v-else>—</span>
                  </template>
                </AppTableColumn>
                <AppTableColumn prop="cashierName" label="收银员" min-width="80" />
              </AppTable>
              <div class="pagination-row">
                <AppPagination
                  v-model:current-page="consumeState.page"
                  :page-size="consumeState.size"
                  :total="consumeState.total"
                  layout="total, prev, pager, next"
                  @current-change="loadConsume"
                />
              </div>
            </AppTabPane>

            <!-- 积分流水（FR-MEM-07） -->
            <AppTabPane label="积分明细" name="points">
              <AppTable :loading="pointsState.loading" :data="pointsState.list" size="small">
                <AppTableColumn label="类型" width="90" align="center">
                  <template #default="{ row }">
                    <UBadge :color="tagColor(POINT_CHANGE_MAP[row.changeType]?.type)" variant="soft" size="sm">
                      {{ POINT_CHANGE_MAP[row.changeType]?.label ?? row.changeType }}
                    </UBadge>
                  </template>
                </AppTableColumn>
                <AppTableColumn label="变动值" width="90" align="right">
                  <template #default="{ row }">
                    <span :class="row.changeValue >= 0 ? 'plus' : 'minus'">
                      {{ row.changeValue >= 0 ? '+' : '' }}{{ row.changeValue }}
                    </span>
                  </template>
                </AppTableColumn>
                <AppTableColumn prop="afterValue" label="变动后余额" width="100" align="right" />
                <AppTableColumn prop="bizNo" label="来源单据" width="160">
                  <template #default="{ row }">{{ row.bizNo || '—' }}</template>
                </AppTableColumn>
                <AppTableColumn prop="operatorName" label="操作人" width="90" />
                <AppTableColumn label="时间" width="150">
                  <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
                </AppTableColumn>
                <AppTableColumn prop="remark" label="备注" min-width="100" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.remark || '—' }}</template>
                </AppTableColumn>
              </AppTable>
              <div class="pagination-row">
                <AppPagination
                  v-model:current-page="pointsState.page"
                  :page-size="pointsState.size"
                  :total="pointsState.total"
                  layout="total, prev, pager, next"
                  @current-change="loadPoints"
                />
              </div>
            </AppTabPane>

            <!-- 储值流水（FR-MEM-08） -->
            <AppTabPane label="储值流水" name="balance">
              <AppTable :loading="balanceState.loading" :data="balanceState.list" size="small">
                <AppTableColumn label="类型" width="90" align="center">
                  <template #default="{ row }">
                    <UBadge :color="tagColor(BALANCE_CHANGE_MAP[row.changeType]?.type)" variant="soft" size="sm">
                      {{ BALANCE_CHANGE_MAP[row.changeType]?.label ?? row.changeType }}
                    </UBadge>
                  </template>
                </AppTableColumn>
                <AppTableColumn label="变动金额" width="100" align="right">
                  <template #default="{ row }">
                    <span :class="row.changeAmount >= 0 ? 'plus' : 'minus'">
                      ￥{{ money(row.changeAmount) }}
                    </span>
                  </template>
                </AppTableColumn>
                <AppTableColumn label="变动后余额" width="100" align="right">
                  <template #default="{ row }">￥{{ money(row.afterBalance) }}</template>
                </AppTableColumn>
                <AppTableColumn label="收款方式" width="85" align="center">
                  <template #default="{ row }">{{ row.payType ? (RECHARGE_PAY_MAP[row.payType] ?? row.payType) : '—' }}</template>
                </AppTableColumn>
                <AppTableColumn prop="bizNo" label="来源单据" width="150">
                  <template #default="{ row }">{{ row.bizNo || '—' }}</template>
                </AppTableColumn>
                <AppTableColumn prop="operatorName" label="操作人" width="90" />
                <AppTableColumn label="授权人" width="90">
                  <template #default="{ row }">{{ row.authorizerName || '—' }}</template>
                </AppTableColumn>
                <AppTableColumn label="时间" width="150">
                  <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
                </AppTableColumn>
              </AppTable>
              <div class="pagination-row">
                <AppPagination
                  v-model:current-page="balanceState.page"
                  :page-size="balanceState.size"
                  :total="balanceState.total"
                  layout="total, prev, pager, next"
                  @current-change="loadBalances"
                />
              </div>
            </AppTabPane>
          </AppTabs>
        </template>
      </div>
    </AppDrawer>

    <!-- Excel 批量导入（FR-MEM-03：校验—确认—部分提交） -->
    <AppModal v-model="importDialog.visible" title="会员批量导入" width="680px" :close-on-click-modal="false">
      <!-- 第一步：上传 -->
      <template v-if="importDialog.step === 'upload'">
        <UAlert
          color="info"
          icon="i-lucide-info"
          title="流程：下载模板 → 填写会员信息 → 上传校验 → 确认提交（仅导入校验通过的行）"
          class="dialog-alert"
        />
        <div class="import-actions">
          <UButton
            color="neutral"
            variant="outline"
            icon="i-lucide-download"
            label="下载导入模板"
            @click="doDownloadTemplate"
          />
        </div>
        <div
          class="import-dropzone"
          @dragover.prevent
          @drop.prevent="onImportDrop"
        >
          <UIcon name="i-lucide-upload" class="size-9 text-muted" />
          <div class="import-dropzone-text">
            拖拽文件到此处 或
            <UButton variant="link" size="sm" label="点击选择 .xlsx 文件" @click="pickImportFile" />
          </div>
          <div v-if="importDialog.file" class="import-file">
            <span class="import-file-name">{{ importDialog.file.name }}</span>
            <UButton
              color="neutral"
              variant="ghost"
              size="xs"
              icon="i-lucide-x"
              aria-label="移除已选文件"
              @click="clearImportFile"
            />
          </div>
          <input
            ref="importFileInputRef"
            type="file"
            accept=".xlsx"
            hidden
            @change="onImportFileInput"
          />
        </div>
      </template>

      <!-- 第二步：校验报告 -->
      <template v-else-if="importDialog.step === 'report'">
        <UAlert
          :color="importDialog.report!.failedCount === 0 ? 'success' : 'warning'"
          icon="i-lucide-info"
          :title="`共 ${importDialog.report!.total} 行：通过 ${importDialog.report!.passedCount} 行，失败 ${importDialog.report!.failedCount} 行`"
          :description="importDialog.report!.failedCount > 0 ? '确认提交将仅导入通过的行；失败行可修正后重新上传。' : '全部校验通过，可确认提交。'"
          class="dialog-alert"
        />
        <AppTable :data="importDialog.report!.rows" size="small" max-height="380">
          <AppTableColumn prop="rowNum" label="行号" width="70" align="center" />
          <AppTableColumn label="结果" width="80" align="center">
            <template #default="{ row }">
              <UBadge :color="row.passed ? 'success' : 'error'" variant="soft" size="sm">
                {{ row.passed ? '通过' : '失败' }}
              </UBadge>
            </template>
          </AppTableColumn>
          <AppTableColumn label="数据" min-width="200">
            <template #default="{ row }">{{ Object.values(row.data).join(' / ') }}</template>
          </AppTableColumn>
          <AppTableColumn label="错误" min-width="180">
            <template #default="{ row }">
              <span v-if="row.errors?.length" class="import-error">{{ row.errors.join('；') }}</span>
              <span v-else>—</span>
            </template>
          </AppTableColumn>
        </AppTable>
      </template>

      <!-- 第三步：提交结果 -->
      <template v-else>
        <div class="import-result">
          <UIcon
            :name="importDialog.result!.failed === 0 ? 'i-lucide-circle-check-big' : 'i-lucide-triangle-alert'"
            :class="['import-result-icon', importDialog.result!.failed === 0 ? 'is-success' : 'is-warning']"
          />
          <div class="import-result-title">
            导入完成：成功 {{ importDialog.result!.imported }} 位，失败 {{ importDialog.result!.failed }} 位
          </div>
          <div v-if="importDialog.result!.failedRows.length" class="import-error-list">
            <div v-for="r in importDialog.result!.failedRows" :key="r.rowNum">
              第 {{ r.rowNum }} 行：{{ r.errors.join('；') }}
            </div>
          </div>
        </div>
      </template>

      <template #footer>
        <!-- 上传步 -->
        <template v-if="importDialog.step === 'upload'">
          <UButton color="neutral" variant="outline" label="取消" @click="importDialog.visible = false" />
          <UButton :loading="importDialog.validating" label="上传校验" @click="doValidate" />
        </template>
        <!-- 校验报告步 -->
        <template v-else-if="importDialog.step === 'report'">
          <UButton color="neutral" variant="outline" label="重新选择文件" @click="importDialog.step = 'upload'" />
          <UButton
            :loading="importDialog.committing"
            :disabled="importDialog.report!.passedCount === 0"
            label="确认提交"
            @click="doCommit"
          />
        </template>
        <!-- 完成步 -->
        <template v-else>
          <UButton label="完成" @click="importDialog.visible = false" />
        </template>
      </template>
    </AppModal>
  </div>
</template>

<style scoped lang="scss">
.page {
  .search-card {
    margin-bottom: 12px;
  }

  .card-head {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .pagination-row {
    display: flex;
    justify-content: flex-end;
    margin-top: 12px;
  }

  .dialog-alert {
    margin-bottom: 12px;
  }

  .form-tip {
    font-size: 12px;
    color: var(--ui-text-muted);
    line-height: 1.4;
  }

  .profile-card {
    margin-bottom: 14px;

    .profile-head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 10px;

      .profile-name {
        font-size: 16px;
        font-weight: 600;
        margin-right: 8px;
      }
    }
  }

  .stat-bar {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 16px;
    margin-bottom: 10px;
    font-size: 13px;
    color: var(--ui-text);

    b {
      color: var(--ui-text);
      font-variant-numeric: tabular-nums;
    }
  }

  .plus {
    color: var(--ui-success);
  }

  .minus {
    color: var(--ui-error);
  }

  .refund {
    color: var(--ui-warning);
  }

  .import-actions {
    display: flex;
    justify-content: center;
    margin-bottom: 14px;
  }

  .import-dropzone {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 6px;
    padding: 28px 16px;
    border: 1px dashed var(--ui-border-accented);
    border-radius: 8px;
    color: var(--ui-text-muted);
    transition: border-color 0.2s;
  }

  .import-dropzone:hover {
    border-color: var(--ui-primary);
  }

  .import-dropzone-text {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: center;
    gap: 2px;
    font-size: 13px;
  }

  .import-file {
    display: flex;
    align-items: center;
    gap: 6px;
    max-width: 100%;
    margin-top: 4px;
    font-size: 13px;
    color: var(--ui-text);
  }

  .import-file-name {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .import-result {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 10px;
    padding: 12px 0 4px;
    text-align: center;
  }

  .import-result-icon {
    width: 40px;
    height: 40px;
  }

  .import-result-icon.is-success {
    color: var(--ui-success);
  }

  .import-result-icon.is-warning {
    color: var(--ui-warning);
  }

  .import-result-title {
    font-size: 15px;
    font-weight: 600;
    color: var(--ui-text);
  }

  .import-error {
    color: var(--ui-error);
    font-size: 12px;
  }

  .import-error-list {
    max-height: 220px;
    overflow: auto;
    font-size: 13px;
    color: var(--ui-error);
    text-align: left;
  }
}
</style>
