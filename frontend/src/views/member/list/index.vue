<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { saveBlob } from '@/api/request'
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
  return v == null ? '—' : Number(v).toFixed(2)
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

function openCreate() {
  editDialog.editing = false
  editDialog.id = 0
  editDialog.form = { name: '', phone: '', gender: undefined, birthday: null, address: '', remark: '' }
  editDialog.visible = true
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
}

/** 手机号被脱敏时禁改（无完整手机号查看权限的角色，BR-MEM-08） */
const phoneDisabled = computed(() => editDialog.editing && editDialog.form.phone.includes('*'))

async function saveMember() {
  const form = editDialog.form
  if (!form.name.trim()) {
    ElMessage.warning('请输入姓名')
    return
  }
  if (!/^1[3-9]\d{9}$/.test(form.phone)) {
    ElMessage.warning('手机号须为大陆 11 位手机号（BR-MEM-01）')
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
      ElMessage.success('会员档案已更新')
    } else {
      const created = await createMember(payload)
      ElMessage.success(`注册成功，会员编号 ${created.memberNo}`)
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
    await ElMessageBox.confirm(
      `停用后【${row.name}】不可再被收银台识别，不享受折扣与积分；历史数据保留。确认停用？`,
      '停用会员',
      { type: 'warning' },
    )
  }
  await updateMemberStatus(row.id, to)
  ElMessage.success(to === 1 ? '已启用' : '已停用')
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
    ElMessage.warning('充值金额必须大于 0')
    return
  }
  if (!form.authorizerUsername.trim()) {
    ElMessage.warning('储值充值须店长授权，请填写授权店长账号（FR-MEM-08）')
    return
  }
  if (form.authorizerUsername.trim() === auth.user?.username) {
    ElMessage.warning('授权人不得为发起人本人')
    return
  }
  if (!form.authorizerPassword) {
    ElMessage.warning('请填写授权店长密码以完成授权（BR-SYS-06）')
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
    ElMessage.success(`充值成功，当前余额 ￥${Number(balance).toFixed(2)}`)
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

async function doValidate() {
  if (!importDialog.file) {
    ElMessage.warning('请先选择导入文件')
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
    ElMessage.success(`成功导入 ${importDialog.result.imported} 位会员`)
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
    <el-card class="search-card">
      <el-form inline @submit.prevent>
        <el-form-item label="会员编号">
          <el-input v-model="query.memberNo" placeholder="M+8位流水" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="query.name" placeholder="姓名模糊" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="query.phone" placeholder="11位手机号" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item v-if="canListLevels" label="等级">
          <el-select v-model="query.levelId" clearable placeholder="全部" style="width: 120px">
            <el-option v-for="l in levelOptions" :key="l.id" :label="l.name" :value="l.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 100px">
            <el-option label="正常" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item label="注册日期">
          <el-date-picker
            v-model="query.dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始"
            end-placeholder="结束"
            style="width: 240px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card>
      <template #header>
        <div class="card-head">
          <span>会员列表（{{ total }}）</span>
          <div>
            <el-button v-if="auth.hasPerm('sys:mem:import')" @click="openImport">批量导入</el-button>
            <el-button v-if="auth.hasPerm('sys:mem:create')" type="primary" @click="openCreate">注册会员</el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" :data="list" stripe>
        <el-table-column prop="memberNo" label="会员编号" width="120" />
        <el-table-column prop="name" label="姓名" width="100" show-overflow-tooltip />
        <el-table-column prop="phone" label="手机号" width="130" />
        <el-table-column prop="levelName" label="等级" width="100">
          <template #default="{ row }">{{ row.levelName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="points" label="积分" width="90" align="right" />
        <el-table-column label="储值余额" width="100" align="right">
          <template #default="{ row }">￥{{ money(row.balance) }}</template>
        </el-table-column>
        <el-table-column label="累计消费" width="100" align="right">
          <template #default="{ row }">￥{{ money(row.totalConsume) }}</template>
        </el-table-column>
        <el-table-column prop="consumeTimes" label="消费次数" width="90" align="right" />
        <el-table-column label="最近消费" width="150">
          <template #default="{ row }">{{ fmtTime(row.lastConsumeTime) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
              {{ row.status === 1 ? '正常' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row as MemberListVO)">详情</el-button>
            <el-button v-if="auth.hasPerm('sys:mem:update')" link type="primary" @click="openEdit(row as MemberListVO)">编辑</el-button>
            <el-button
              v-if="auth.hasPerm('sys:mem:recharge') && row.status === 1"
              link
              type="warning"
              @click="openRecharge(row as MemberListVO)"
            >充值</el-button>
            <el-button
              v-if="auth.hasPerm('sys:mem:status')"
              link
              :type="row.status === 1 ? 'danger' : 'success'"
              @click="toggleStatus(row as MemberListVO)"
            >{{ row.status === 1 ? '停用' : '启用' }}</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-row">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="load"
          @size-change="search"
        />
      </div>
    </el-card>

    <!-- 注册 / 编辑（FR-MEM-01/02） -->
    <el-dialog
      v-model="editDialog.visible"
      :title="editDialog.editing ? '编辑会员' : '注册会员'"
      width="480px"
      :close-on-click-modal="false"
    >
      <el-alert
        v-if="editDialog.editing"
        type="info"
        :closable="false"
        show-icon
        title="积分、累计消费额、储值余额不可手工修改，只能通过业务单据产生（FR-MEM-02）"
        class="dialog-alert"
      />
      <el-form label-width="80px" @submit.prevent>
        <el-form-item label="姓名" required>
          <el-input v-model="editDialog.form.name" maxlength="30" placeholder="会员姓名" />
        </el-form-item>
        <el-form-item label="手机号" required>
          <el-input
            v-model="editDialog.form.phone"
            :disabled="phoneDisabled"
            maxlength="11"
            placeholder="大陆 11 位手机号（唯一识别键）"
          />
          <div v-if="phoneDisabled" class="form-tip">
            手机号已按角色权限脱敏展示（BR-MEM-08），如需修改请联系店长
          </div>
        </el-form-item>
        <el-form-item label="性别">
          <el-radio-group v-model="editDialog.form.gender">
            <el-radio :value="1">男</el-radio>
            <el-radio :value="2">女</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="生日">
          <el-date-picker
            v-model="editDialog.form.birthday"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选填（生日提醒用）"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="editDialog.form.address" maxlength="200" placeholder="选填" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="editDialog.form.remark" type="textarea" :rows="2" maxlength="200" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="editDialog.saving" @click="saveMember">保存</el-button>
      </template>
    </el-dialog>

    <!-- 储值充值（FR-MEM-08） -->
    <el-dialog
      v-model="rechargeDialog.visible"
      :title="`储值充值：${rechargeDialog.member?.name ?? ''}（余额 ￥${money(rechargeDialog.member?.balance)}）`"
      width="440px"
      :close-on-click-modal="false"
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="充值须店长授权（输入店长账号+密码，授权人 ≠ 发起人）；充值不计积分，余额变动全部写入不可删改流水"
        class="dialog-alert"
      />
      <el-form label-width="80px" @submit.prevent>
        <el-form-item label="充值金额" required>
          <el-input-number
            v-model="rechargeDialog.form.amount"
            :min="0.01"
            :precision="2"
            :controls="false"
            placeholder="元"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="收款方式" required>
          <el-radio-group v-model="rechargeDialog.form.payType">
            <el-radio v-for="(label, type) in RECHARGE_PAY_MAP" :key="type" :value="type">{{ label }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="授权账号" required>
          <el-input v-model="rechargeDialog.form.authorizerUsername" maxlength="30" placeholder="店长登录账号" />
        </el-form-item>
        <el-form-item label="授权密码" required>
          <el-input
            v-model="rechargeDialog.form.authorizerPassword"
            type="password"
            show-password
            maxlength="50"
            placeholder="店长账号密码（现场授权，BR-SYS-06）"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="rechargeDialog.form.remark" maxlength="200" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rechargeDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="rechargeDialog.saving" @click="saveRecharge">确认充值</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉（FR-MEM-03/04/07/08） -->
    <el-drawer v-model="drawer.visible" size="720px" :title="`会员详情：${drawer.member?.name ?? ''}`">
      <div v-loading="drawer.loading">
        <template v-if="drawer.member">
          <div class="profile-card">
            <div class="profile-head">
              <div>
                <span class="profile-name">{{ drawer.member.name }}</span>
                <el-tag size="small">{{ drawer.member.levelName || '普通会员' }}</el-tag>
                <el-tag :type="drawer.member.status === 1 ? 'success' : 'danger'" size="small">
                  {{ drawer.member.status === 1 ? '正常' : '停用' }}
                </el-tag>
              </div>
              <el-button
                v-if="auth.hasPerm('sys:mem:recharge') && drawer.member.status === 1"
                type="warning"
                size="small"
                @click="openRecharge(drawer.member)"
              >储值充值</el-button>
            </div>
            <el-descriptions :column="3" size="small" border class="profile-desc">
              <el-descriptions-item label="会员编号">{{ drawer.member.memberNo }}</el-descriptions-item>
              <el-descriptions-item label="手机号">{{ drawer.member.phone }}</el-descriptions-item>
              <el-descriptions-item label="当前积分">{{ drawer.member.points }}</el-descriptions-item>
              <el-descriptions-item label="储值余额">￥{{ money(drawer.member.balance) }}</el-descriptions-item>
              <el-descriptions-item label="累计消费">￥{{ money(drawer.member.totalConsume) }}</el-descriptions-item>
              <el-descriptions-item label="消费次数">{{ drawer.member.consumeTimes }}</el-descriptions-item>
              <el-descriptions-item label="性别">
                {{ drawer.member.gender === 1 ? '男' : drawer.member.gender === 2 ? '女' : '—' }}
              </el-descriptions-item>
              <el-descriptions-item label="生日">{{ drawer.member.birthday ?? '—' }}</el-descriptions-item>
              <el-descriptions-item label="最近消费">{{ fmtTime(drawer.member.lastConsumeTime) }}</el-descriptions-item>
              <el-descriptions-item label="注册人">{{ drawer.member.registerName ?? '—' }}</el-descriptions-item>
              <el-descriptions-item label="注册时间">{{ fmtTime(drawer.member.registerTime) }}</el-descriptions-item>
              <el-descriptions-item label="地址">{{ drawer.member.address || '—' }}</el-descriptions-item>
              <el-descriptions-item label="备注" :span="3">{{ drawer.member.remark || '—' }}</el-descriptions-item>
            </el-descriptions>
          </div>

          <el-tabs v-model="drawer.tab" @tab-change="onDrawerTab">
            <!-- 消费记录（FR-MEM-04） -->
            <el-tab-pane label="消费记录" name="consume">
              <div class="stat-bar">
                <el-date-picker
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
              <el-table v-loading="consumeState.loading" :data="consumeState.list" size="small">
                <el-table-column label="类型" width="70" align="center">
                  <template #default="{ row }">
                    <el-tag :type="row.type === 'SALE' ? 'primary' : 'warning'" size="small">
                      {{ row.type === 'SALE' ? '销售' : '退货' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="orderNo" label="单号" width="160" />
                <el-table-column label="时间" width="150">
                  <template #default="{ row }">{{ fmtTime(row.time) }}</template>
                </el-table-column>
                <el-table-column label="商品总额" width="95" align="right">
                  <template #default="{ row }">￥{{ money(row.goodsAmount) }}</template>
                </el-table-column>
                <el-table-column label="优惠" width="85" align="right">
                  <template #default="{ row }">￥{{ money(row.discountAmount) }}</template>
                </el-table-column>
                <el-table-column label="实付/退款" width="95" align="right">
                  <template #default="{ row }">
                    <span :class="row.type === 'RETURN' ? 'refund' : ''">￥{{ money(row.amount) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="积分变动" width="85" align="right">
                  <template #default="{ row }">
                    <span v-if="row.pointsChange != null" :class="row.pointsChange >= 0 ? 'plus' : 'minus'">
                      {{ row.pointsChange >= 0 ? '+' : '' }}{{ row.pointsChange }}
                    </span>
                    <span v-else>—</span>
                  </template>
                </el-table-column>
                <el-table-column prop="cashierName" label="收银员" min-width="80" />
              </el-table>
              <div class="pagination-row">
                <el-pagination
                  v-model:current-page="consumeState.page"
                  :page-size="consumeState.size"
                  :total="consumeState.total"
                  layout="total, prev, pager, next"
                  @current-change="loadConsume"
                />
              </div>
            </el-tab-pane>

            <!-- 积分流水（FR-MEM-07） -->
            <el-tab-pane label="积分明细" name="points">
              <el-table v-loading="pointsState.loading" :data="pointsState.list" size="small">
                <el-table-column label="类型" width="90" align="center">
                  <template #default="{ row }">
                    <el-tag :type="POINT_CHANGE_MAP[row.changeType]?.type ?? 'info'" size="small">
                      {{ POINT_CHANGE_MAP[row.changeType]?.label ?? row.changeType }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="变动值" width="90" align="right">
                  <template #default="{ row }">
                    <span :class="row.changeValue >= 0 ? 'plus' : 'minus'">
                      {{ row.changeValue >= 0 ? '+' : '' }}{{ row.changeValue }}
                    </span>
                  </template>
                </el-table-column>
                <el-table-column prop="afterValue" label="变动后余额" width="100" align="right" />
                <el-table-column prop="bizNo" label="来源单据" width="160">
                  <template #default="{ row }">{{ row.bizNo || '—' }}</template>
                </el-table-column>
                <el-table-column prop="operatorName" label="操作人" width="90" />
                <el-table-column label="时间" width="150">
                  <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
                </el-table-column>
                <el-table-column prop="remark" label="备注" min-width="100" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.remark || '—' }}</template>
                </el-table-column>
              </el-table>
              <div class="pagination-row">
                <el-pagination
                  v-model:current-page="pointsState.page"
                  :page-size="pointsState.size"
                  :total="pointsState.total"
                  layout="total, prev, pager, next"
                  @current-change="loadPoints"
                />
              </div>
            </el-tab-pane>

            <!-- 储值流水（FR-MEM-08） -->
            <el-tab-pane label="储值流水" name="balance">
              <el-table v-loading="balanceState.loading" :data="balanceState.list" size="small">
                <el-table-column label="类型" width="90" align="center">
                  <template #default="{ row }">
                    <el-tag :type="BALANCE_CHANGE_MAP[row.changeType]?.type ?? 'info'" size="small">
                      {{ BALANCE_CHANGE_MAP[row.changeType]?.label ?? row.changeType }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="变动金额" width="100" align="right">
                  <template #default="{ row }">
                    <span :class="row.changeAmount >= 0 ? 'plus' : 'minus'">
                      ￥{{ Number(row.changeAmount).toFixed(2) }}
                    </span>
                  </template>
                </el-table-column>
                <el-table-column label="变动后余额" width="100" align="right">
                  <template #default="{ row }">￥{{ money(row.afterBalance) }}</template>
                </el-table-column>
                <el-table-column label="收款方式" width="85" align="center">
                  <template #default="{ row }">{{ row.payType ? (RECHARGE_PAY_MAP[row.payType] ?? row.payType) : '—' }}</template>
                </el-table-column>
                <el-table-column prop="bizNo" label="来源单据" width="150">
                  <template #default="{ row }">{{ row.bizNo || '—' }}</template>
                </el-table-column>
                <el-table-column prop="operatorName" label="操作人" width="90" />
                <el-table-column label="授权人" width="90">
                  <template #default="{ row }">{{ row.authorizerName || '—' }}</template>
                </el-table-column>
                <el-table-column label="时间" width="150">
                  <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
                </el-table-column>
              </el-table>
              <div class="pagination-row">
                <el-pagination
                  v-model:current-page="balanceState.page"
                  :page-size="balanceState.size"
                  :total="balanceState.total"
                  layout="total, prev, pager, next"
                  @current-change="loadBalances"
                />
              </div>
            </el-tab-pane>
          </el-tabs>
        </template>
      </div>
    </el-drawer>

    <!-- Excel 批量导入（FR-MEM-03：校验—确认—部分提交） -->
    <el-dialog v-model="importDialog.visible" title="会员批量导入" width="680px" :close-on-click-modal="false">
      <!-- 第一步：上传 -->
      <template v-if="importDialog.step === 'upload'">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="流程：下载模板 → 填写会员信息 → 上传校验 → 确认提交（仅导入校验通过的行）"
          class="dialog-alert"
        />
        <div class="import-actions">
          <el-button @click="doDownloadTemplate">
            <el-icon><Download /></el-icon>下载导入模板
          </el-button>
        </div>
        <el-upload
          drag
          :auto-upload="false"
          :limit="1"
          accept=".xlsx"
          :on-change="onImportFileChange"
          :on-remove="() => (importDialog.file = null)"
        >
          <el-icon :size="36"><UploadFilled /></el-icon>
          <div class="el-upload__text">拖拽文件到此处 或 <em>点击选择 .xlsx 文件</em></div>
        </el-upload>
      </template>

      <!-- 第二步：校验报告 -->
      <template v-else-if="importDialog.step === 'report'">
        <el-alert
          :type="importDialog.report!.failedCount === 0 ? 'success' : 'warning'"
          :closable="false"
          show-icon
          :title="`共 ${importDialog.report!.total} 行：通过 ${importDialog.report!.passedCount} 行，失败 ${importDialog.report!.failedCount} 行`"
          :description="importDialog.report!.failedCount > 0 ? '确认提交将仅导入通过的行；失败行可修正后重新上传。' : '全部校验通过，可确认提交。'"
          class="dialog-alert"
        />
        <el-table :data="importDialog.report!.rows" size="small" max-height="380">
          <el-table-column prop="rowNum" label="行号" width="70" align="center" />
          <el-table-column label="结果" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.passed ? 'success' : 'danger'" size="small">
                {{ row.passed ? '通过' : '失败' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="数据" min-width="200">
            <template #default="{ row }">{{ Object.values(row.data).join(' / ') }}</template>
          </el-table-column>
          <el-table-column label="错误" min-width="180">
            <template #default="{ row }">
              <span v-if="row.errors?.length" class="import-error">{{ row.errors.join('；') }}</span>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <!-- 第三步：提交结果 -->
      <template v-else>
        <el-result
          :icon="importDialog.result!.failed === 0 ? 'success' : 'warning'"
          :title="`导入完成：成功 ${importDialog.result!.imported} 位，失败 ${importDialog.result!.failed} 位`"
        >
          <template v-if="importDialog.result!.failedRows.length" #sub-title>
            <div class="import-error-list">
              <div v-for="r in importDialog.result!.failedRows" :key="r.rowNum">
                第 {{ r.rowNum }} 行：{{ r.errors.join('；') }}
              </div>
            </div>
          </template>
        </el-result>
      </template>

      <template #footer>
        <!-- 上传步 -->
        <template v-if="importDialog.step === 'upload'">
          <el-button @click="importDialog.visible = false">取消</el-button>
          <el-button type="primary" :loading="importDialog.validating" @click="doValidate">上传校验</el-button>
        </template>
        <!-- 校验报告步 -->
        <template v-else-if="importDialog.step === 'report'">
          <el-button @click="importDialog.step = 'upload'">重新选择文件</el-button>
          <el-button
            type="primary"
            :loading="importDialog.committing"
            :disabled="importDialog.report!.passedCount === 0"
            @click="doCommit"
          >确认提交</el-button>
        </template>
        <!-- 完成步 -->
        <template v-else>
          <el-button type="primary" @click="importDialog.visible = false">完成</el-button>
        </template>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.page {
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
    color: #909399;
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
    color: #606266;

    b {
      color: #303133;
      font-variant-numeric: tabular-nums;
    }
  }

  .plus {
    color: #67c23a;
  }

  .minus {
    color: #f56c6c;
  }

  .refund {
    color: #e6a23c;
  }

  .import-actions {
    display: flex;
    justify-content: center;
    margin-bottom: 14px;
  }

  .import-error {
    color: #f56c6c;
    font-size: 12px;
  }

  .import-error-list {
    max-height: 220px;
    overflow: auto;
    font-size: 13px;
    color: #f56c6c;
    text-align: left;
  }
}
</style>
