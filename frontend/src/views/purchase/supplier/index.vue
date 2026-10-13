<script setup lang="ts">
import { moneyText } from '@/utils/format'
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import {
  SETTLE_TYPE_MAP,
  createSupplier,
  getSupplierDetail,
  getSupplierGoods,
  pageSuppliers,
  saveSupplierGoods,
  updateSupplier,
  updateSupplierStatus,
  type SupplierListVO,
} from '@/api/supplier'
import { getCategoryTree, pageGoods, type CategoryNode, type GoodsListVO } from '@/api/goods'
import { getDictItems } from '@/api/system'

const auth = useAuthStore()

function fmtTime(v: string | null | undefined) {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}

function money(v: number | null | undefined) {
  return v == null ? '—' : moneyText(v, 2)
}

/** 结算方式选项（FR-SYS-07/GAP-P1-09：取自数据字典 settle_type 启用项，字典缺失时回退内置展示） */
const settleTypeOptions = ref<{ label: string; value: string }[]>(
  Object.entries(SETTLE_TYPE_MAP).map(([value, label]) => ({ label, value })),
)
const settleTypeLabels = ref<Record<string, string>>({ ...SETTLE_TYPE_MAP })

async function loadSettleTypes() {
  try {
    const items = await getDictItems('settle_type')
    if (items.length) {
      settleTypeOptions.value = items.map((i) => ({ label: i.label, value: i.value }))
      settleTypeLabels.value = Object.fromEntries(items.map((i) => [i.value, i.label]))
    }
  } catch {
    // 回退内置展示，后端保存时仍按字典校验
  }
}

/** 结算方式展示：账期附带天数；名称取自字典 */
function settleLabel(row: SupplierListVO) {
  const label = settleTypeLabels.value[row.settleType] ?? row.settleType
  if (row.settleType === 'CREDIT') return `账期${row.creditDays}天`
  return label
}

// ---------- 查询（FR-SUP-02） ----------
const loading = ref(false)
const list = ref<SupplierListVO[]>([])
const total = ref(0)
const query = reactive({
  name: '',
  code: '',
  contactPerson: '',
  phone: '',
  status: null as number | null,
  categoryId: null as number | null,
  page: 1,
  size: 20,
})

/** 供货品类筛选：分类树（与商品档案一致） */
const categoryTree = ref<CategoryNode[]>([])

async function load() {
  loading.value = true
  try {
    const data = await pageSuppliers({
      name: query.name.trim() || undefined,
      code: query.code.trim() || undefined,
      contactPerson: query.contactPerson.trim() || undefined,
      phone: query.phone.trim() || undefined,
      status: query.status ?? undefined,
      categoryId: query.categoryId ?? undefined,
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
    name: '',
    code: '',
    contactPerson: '',
    phone: '',
    status: null,
    categoryId: null,
    page: 1,
  })
  load()
}

onMounted(() => {
  load()
  getCategoryTree().then((tree) => (categoryTree.value = tree))
  loadSettleTypes()
})

// ---------- 新增 / 编辑（FR-SUP-01） ----------
const editDialog = reactive({
  visible: false,
  saving: false,
  editing: false,
  id: 0,
  form: {
    name: '',
    contactPerson: '',
    phone: '',
    address: '',
    email: '',
    settleType: 'CASH',
    creditDays: null as number | null,
    leadTimeDays: 3,
    bankName: '',
    bankAccount: '',
    taxNo: '',
    remark: '',
  },
})

/** 敏感字段被脱敏时禁改（无银行账号/纳税人识别号查看权限的角色，BR-SUP-01） */
const sensitiveMasked = ref(false)

function openCreate() {
  editDialog.editing = false
  editDialog.id = 0
  sensitiveMasked.value = false
  editDialog.form = {
    name: '',
    contactPerson: '',
    phone: '',
    address: '',
    email: '',
    settleType: 'CASH',
    creditDays: null,
    leadTimeDays: 3,
    bankName: '',
    bankAccount: '',
    taxNo: '',
    remark: '',
  }
  editDialog.visible = true
}

async function openEdit(row: SupplierListVO) {
  // 以详情接口取最新档案（列表行数据可能滞后）
  const detail = await getSupplierDetail(row.id)
  editDialog.editing = true
  editDialog.id = row.id
  sensitiveMasked.value = (detail.bankAccount ?? '').includes('*')
  editDialog.form = {
    name: detail.name,
    contactPerson: detail.contactPerson ?? '',
    phone: detail.phone ?? '',
    address: detail.address ?? '',
    email: detail.email ?? '',
    settleType: detail.settleType,
    creditDays: detail.creditDays,
    leadTimeDays: detail.leadTimeDays,
    bankName: detail.bankName ?? '',
    bankAccount: detail.bankAccount ?? '',
    taxNo: sensitiveMasked.value ? '' : (detail.taxNo ?? ''),
    remark: detail.remark ?? '',
  }
  editDialog.visible = true
}

async function saveSupplier() {
  const form = editDialog.form
  if (!form.name.trim()) {
    ElMessage.warning('请输入供应商名称')
    return
  }
  if (form.phone && !/^1[3-9]\d{9}$/.test(form.phone) && !/^0\d{2,3}-?\d{7,8}$/.test(form.phone)) {
    ElMessage.warning('联系电话须为大陆手机号或固话格式（BR-SUP-01）')
    return
  }
  if (form.settleType === 'CREDIT' && (form.creditDays == null || form.creditDays < 1)) {
    ElMessage.warning('结算方式为账期时必须填写账期天数')
    return
  }
  editDialog.saving = true
  try {
    const payload = {
      name: form.name.trim(),
      contactPerson: form.contactPerson.trim() || undefined,
      phone: form.phone.trim() || undefined,
      address: form.address.trim() || undefined,
      email: form.email.trim() || undefined,
      settleType: form.settleType,
      creditDays: form.settleType === 'CREDIT' ? form.creditDays! : undefined,
      leadTimeDays: form.leadTimeDays,
      bankName: form.bankName.trim() || undefined,
      // 脱敏展示时留空提交，后端保留库中原值（BR-SUP-01）
      bankAccount: sensitiveMasked.value ? undefined : form.bankAccount.trim() || undefined,
      taxNo: sensitiveMasked.value ? undefined : form.taxNo.trim() || undefined,
      remark: form.remark.trim() || undefined,
    }
    if (editDialog.editing) {
      await updateSupplier(editDialog.id, payload)
      ElMessage.success('供应商档案已更新')
    } else {
      const created = await createSupplier(payload)
      ElMessage.success(`新增成功，供应商编码 ${created.supplierCode}`)
    }
    editDialog.visible = false
    load()
  } finally {
    editDialog.saving = false
  }
}

// ---------- 停用 / 启用（FR-SUP-03） ----------
async function toggleStatus(row: SupplierListVO) {
  const to = row.status === 1 ? 0 : 1
  if (to === 0) {
    await ElMessageBox.confirm(
      `停用后【${row.name}】不可被新采购单选择；历史单据与结算记录保留可查。确认停用？`,
      '停用供应商',
      { type: 'warning' },
    )
  }
  await updateSupplierStatus(row.id, to)
  ElMessage.success(to === 1 ? '已启用' : '已停用')
  load()
}

// ---------- 供货商品维护（FR-SUP-04） ----------
interface GoodsRow {
  goodsId: number
  goodsCode: string
  goodsName: string
  spec: string | null
  categoryName: string | null
  unitName: string | null
  supplyPrice: number | null
  isDefault: number
  lastSupplyTime: string | null
}

const goodsDialog = reactive({
  visible: false,
  saving: false,
  loading: false,
  supplier: null as SupplierListVO | null,
  rows: [] as GoodsRow[],
  keyword: '',
  searching: false,
  options: [] as GoodsListVO[],
})

async function openGoods(row: SupplierListVO) {
  goodsDialog.supplier = row
  goodsDialog.keyword = ''
  goodsDialog.options = []
  goodsDialog.visible = true
  goodsDialog.loading = true
  try {
    const items = await getSupplierGoods(row.id)
    goodsDialog.rows = items.map((it) => ({
      goodsId: it.goodsId,
      goodsCode: it.goodsCode,
      goodsName: it.goodsName,
      spec: it.spec,
      categoryName: it.categoryName,
      unitName: it.unitName,
      supplyPrice: it.supplyPrice,
      isDefault: it.isDefault,
      lastSupplyTime: it.lastSupplyTime,
    }))
  } finally {
    goodsDialog.loading = false
  }
}

/** 远程搜索商品（FR-SUP-04：从全量商品中选择） */
async function searchGoods(kw: string) {
  goodsDialog.searching = true
  try {
    const data = await pageGoods({
      name: kw.trim() || undefined,
      status: 1,
      page: 1,
      size: 20,
    })
    goodsDialog.options = data.list
  } finally {
    goodsDialog.searching = false
  }
}

function addGoods(g: GoodsListVO) {
  if (goodsDialog.rows.some((r) => r.goodsId === g.id)) {
    ElMessage.warning('该商品已在供货清单中')
    return
  }
  goodsDialog.rows.push({
    goodsId: g.id,
    goodsCode: g.goodsCode,
    goodsName: g.name,
    spec: g.spec,
    categoryName: g.categoryName,
    unitName: g.unitName,
    supplyPrice: g.purchasePrice,
    isDefault: 0,
    lastSupplyTime: null,
  })
  goodsDialog.keyword = ''
  goodsDialog.options = []
}

function removeRow(index: number) {
  goodsDialog.rows.splice(index, 1)
}

async function saveGoods() {
  if (!goodsDialog.supplier) return
  goodsDialog.saving = true
  try {
    await saveSupplierGoods(
      goodsDialog.supplier.id,
      goodsDialog.rows.map((r) => ({
        goodsId: r.goodsId,
        supplyPrice: r.supplyPrice ?? undefined,
        isDefault: r.isDefault,
      })),
    )
    ElMessage.success('供货商品已保存')
    goodsDialog.visible = false
    load()
  } finally {
    goodsDialog.saving = false
  }
}
</script>

<template>
  <div class="page">
    <!-- 筛选区（FR-SUP-02） -->
    <el-card class="search-card">
      <el-form inline @submit.prevent>
        <el-form-item label="名称">
          <el-input v-model="query.name" placeholder="供应商名称模糊" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="编码">
          <el-input v-model="query.code" placeholder="S+5位流水" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="联系人">
          <el-input v-model="query.contactPerson" placeholder="联系人模糊" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="电话">
          <el-input v-model="query.phone" placeholder="手机/固话" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 100px">
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item label="供货品类">
          <el-tree-select
            v-model="query.categoryId"
            :data="categoryTree"
            :props="{ label: 'name', children: 'children' }"
            node-key="id"
            check-strictly
            clearable
            placeholder="按供货商品分类"
            style="width: 180px"
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
          <span>供应商列表（{{ total }}）</span>
          <el-button v-if="auth.hasPerm('sys:sup:create')" type="primary" @click="openCreate">新增供应商</el-button>
        </div>
      </template>

      <el-table v-loading="loading" border :data="list" stripe>
        <el-table-column prop="supplierCode" label="编码" width="90" />
        <el-table-column prop="name" label="供应商名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="contactPerson" label="联系人" width="90">
          <template #default="{ row }">{{ row.contactPerson || '—' }}</template>
        </el-table-column>
        <el-table-column prop="phone" label="联系电话" width="130">
          <template #default="{ row }">{{ row.phone || '—' }}</template>
        </el-table-column>
        <el-table-column label="结算方式" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.settleType === 'CREDIT' ? 'warning' : 'info'">
              {{ settleLabel(row as SupplierListVO) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="提前期" width="80" align="right">
          <template #default="{ row }">{{ row.leadTimeDays }}天</template>
        </el-table-column>
        <el-table-column label="累计采购额" width="110" align="right">
          <template #default="{ row }">￥{{ money(row.totalPurchaseAmount) }}</template>
        </el-table-column>
        <el-table-column label="未结算金额" width="110" align="right">
          <template #default="{ row }">￥{{ money(row.unsettledAmount) }}</template>
        </el-table-column>
        <el-table-column prop="inTransitCount" label="在途单" width="80" align="right" />
        <el-table-column prop="goodsCount" label="供货商品" width="85" align="right" />
        <el-table-column label="最近合作" width="150">
          <template #default="{ row }">{{ fmtTime(row.lastCoopTime) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="250" fixed="right" align="center">
          <template #default="{ row }">
            <el-button v-if="auth.hasPerm('sys:sup:update')" link type="primary" @click="openEdit(row as SupplierListVO)">编辑</el-button>
            <el-button v-if="auth.hasPerm('sys:sup:goods')" link type="primary" @click="openGoods(row as SupplierListVO)">供货商品</el-button>
            <el-button
              v-if="auth.hasPerm('sys:sup:status')"
              link
              :type="row.status === 1 ? 'danger' : 'success'"
              @click="toggleStatus(row as SupplierListVO)"
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

    <!-- 新增 / 编辑（FR-SUP-01） -->
    <el-dialog
      v-model="editDialog.visible"
      :title="editDialog.editing ? '编辑供应商' : '新增供应商'"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-alert
        v-if="editDialog.editing"
        type="info"
        :closable="false"
        show-icon
        title="供应商编码由系统生成，创建后不可修改（BR-SUP-03）；累计采购额由采购入库业务回写，不可手工修改"
        class="dialog-alert"
      />
      <el-form label-width="90px" @submit.prevent>
        <el-form-item label="名称" required>
          <el-input v-model="editDialog.form.name" maxlength="100" placeholder="供应商名称（唯一，BR-SUP-01）" />
        </el-form-item>
        <el-form-item label="联系人">
          <el-input v-model="editDialog.form.contactPerson" maxlength="30" placeholder="选填" />
        </el-form-item>
        <el-form-item label="联系电话">
          <el-input v-model="editDialog.form.phone" maxlength="20" placeholder="手机号或固话（0xx-xxxxxxxx）" />
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="editDialog.form.address" maxlength="200" placeholder="选填" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="editDialog.form.email" maxlength="50" placeholder="选填" />
        </el-form-item>
        <el-form-item label="结算方式" required>
          <el-radio-group v-model="editDialog.form.settleType">
            <el-radio v-for="t in settleTypeOptions" :key="t.value" :value="t.value">{{ t.label }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="editDialog.form.settleType === 'CREDIT'" label="账期天数" required>
          <el-input-number
            v-model="editDialog.form.creditDays"
            :min="1"
            :max="365"
            :controls="false"
            placeholder="天"
            style="width: 160px"
          />
        </el-form-item>
        <el-form-item label="采购提前期" required>
          <el-input-number
            v-model="editDialog.form.leadTimeDays"
            :min="0"
            :max="365"
            :controls="false"
            placeholder="天，参与建议采购量（BR-INV-07）"
            style="width: 160px"
          />
        </el-form-item>
        <el-form-item label="开户行">
          <el-input v-model="editDialog.form.bankName" maxlength="100" placeholder="选填" />
        </el-form-item>
        <el-form-item label="银行账号">
          <el-input
            v-model="editDialog.form.bankAccount"
            :disabled="sensitiveMasked"
            maxlength="50"
            placeholder="仅店长及以上可见（BR-SUP-01）"
          />
          <div v-if="sensitiveMasked" class="form-tip">
            银行账号已按角色权限脱敏展示，如需修改请联系店长
          </div>
        </el-form-item>
        <el-form-item label="纳税人识别号">
          <el-input
            v-model="editDialog.form.taxNo"
            :disabled="sensitiveMasked"
            maxlength="30"
            placeholder="仅店长及以上可见（BR-SUP-01）"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="editDialog.form.remark" type="textarea" :rows="2" maxlength="500" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="editDialog.saving" @click="saveSupplier">保存</el-button>
      </template>
    </el-dialog>

    <!-- 供货商品维护（FR-SUP-04） -->
    <el-dialog
      v-model="goodsDialog.visible"
      :title="`维护供货商品：${goodsDialog.supplier?.name ?? ''}`"
      width="880px"
      :close-on-click-modal="false"
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="一个商品可对应多个供应商并指定默认供应商；设为默认后，该商品在其他供应商上的默认标记自动取消。最近供货时间由采购入库回写，人工不可修改"
        class="dialog-alert"
      />
      <div class="goods-add">
        <span class="goods-add-label">添加商品：</span>
        <el-select
          v-model="goodsDialog.keyword"
          filterable
          remote
          clearable
          placeholder="按商品名称搜索后选择"
          :remote-method="searchGoods"
          :loading="goodsDialog.searching"
          style="width: 320px"
          @change="addGoods"
        >
          <el-option
            v-for="g in goodsDialog.options"
            :key="g.id"
            :value="g.id"
            :label="`${g.name}（${g.goodsCode}）`"
          >
            <span>{{ g.name }}</span>
            <span class="goods-option-sub">{{ g.goodsCode }} · {{ g.categoryName }} · 零售 ￥{{ money(g.retailPrice) }}</span>
          </el-option>
        </el-select>
      </div>
      <el-table v-loading="goodsDialog.loading" :data="goodsDialog.rows" size="small" max-height="380">
        <el-table-column prop="goodsCode" label="商品编码" width="110" />
        <el-table-column prop="goodsName" label="商品名称" min-width="150" show-overflow-tooltip />
        <el-table-column prop="spec" label="规格" width="100">
          <template #default="{ row }">{{ row.spec || '—' }}</template>
        </el-table-column>
        <el-table-column prop="categoryName" label="分类" width="100">
          <template #default="{ row }">{{ row.categoryName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="unitName" label="单位" width="70" align="center">
          <template #default="{ row }">{{ row.unitName || '—' }}</template>
        </el-table-column>
        <el-table-column label="供货单价" width="140" align="right">
          <template #default="{ row }">
            <el-input-number
              v-model="row.supplyPrice"
              :min="0"
              :precision="2"
              :controls="false"
              size="small"
              placeholder="选填"
              style="width: 120px"
            />
          </template>
        </el-table-column>
        <el-table-column label="默认供应商" width="100" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.isDefault" :active-value="1" :inactive-value="0" />
          </template>
        </el-table-column>
        <el-table-column label="最近供货价" width="90" align="right">
          <template #default="{ row }">
            {{ money(row.lastSupplyPrice) }}
          </template>
        </el-table-column>
        <el-table-column label="最近供货" width="150">
          <template #default="{ row }">{{ fmtTime(row.lastSupplyTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="70" align="center">
          <template #default="{ $index }">
            <el-button link type="danger" @click="removeRow($index)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="goodsDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="goodsDialog.saving" @click="saveGoods">保存</el-button>
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

  .goods-add {
    display: flex;
    align-items: center;
    margin-bottom: 12px;

    .goods-add-label {
      font-size: 13px;
      color: #606266;
    }
  }

  .goods-option-sub {
    float: right;
    font-size: 12px;
    color: #909399;
  }
}
</style>
