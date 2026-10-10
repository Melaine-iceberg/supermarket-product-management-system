<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import {
  createLevel,
  listLevels,
  updateLevel,
  updateLevelStatus,
  type MemberLevelVO,
} from '@/api/memberLevel'

const auth = useAuthStore()

function fmtTime(v: string | null | undefined) {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}

function money(v: number | null | undefined) {
  return v == null ? '—' : Number(v).toFixed(2)
}

const canUpdate = () => auth.hasPerm('sys:mem-level:update')

// 操作人展示：种子/系统内置数据写入的 'system' 显示为“系统”，避免中文界面出现英文
function operatorName(v: string | null | undefined) {
  if (!v) return '—'
  return v === 'system' ? '系统' : v
}

// ---------- 列表（FR-MEM-05） ----------
const loading = ref(false)
const list = ref<MemberLevelVO[]>([])

async function load() {
  loading.value = true
  try {
    list.value = await listLevels()
  } finally {
    loading.value = false
  }
}

onMounted(load)

// ---------- 新增 / 编辑 ----------
const editDialog = reactive({
  visible: false,
  saving: false,
  editing: false,
  id: 0,
  form: {
    name: '',
    threshold: null as number | null,
    discountRate: null as number | null,
    pointRate: 1 as number | null,
    sortNo: null as number | null,
    status: 1,
  },
})

function openCreate() {
  editDialog.editing = false
  editDialog.id = 0
  editDialog.form = { name: '', threshold: 0, discountRate: 1, pointRate: 1, sortNo: null, status: 1 }
  editDialog.visible = true
}

function openEdit(row: MemberLevelVO) {
  editDialog.editing = true
  editDialog.id = row.id
  editDialog.form = {
    name: row.name,
    threshold: Number(row.threshold),
    discountRate: Number(row.discountRate),
    pointRate: Number(row.pointRate),
    sortNo: row.sortNo,
    status: row.status,
  }
  editDialog.visible = true
}

async function saveLevel() {
  const form = editDialog.form
  if (!form.name.trim()) {
    ElMessage.warning('请输入等级名称')
    return
  }
  if (form.threshold == null || form.threshold < 0) {
    ElMessage.warning('升级门槛不能为负数（BR-MEM-06 判定依据）')
    return
  }
  if (form.discountRate == null || form.discountRate <= 0 || form.discountRate > 1) {
    ElMessage.warning('折扣率须在 (0, 1] 之间，如 0.98 = 98 折（BR-SALE-10）')
    return
  }
  if (form.pointRate == null || form.pointRate <= 0 || form.pointRate > 99) {
    ElMessage.warning('积分倍率须在 (0, 99] 之间，如 1.2（BR-MEM-05）')
    return
  }
  if (form.sortNo == null || form.sortNo < 0) {
    ElMessage.warning('请填写排序号（门槛升序展示）')
    return
  }
  editDialog.saving = true
  try {
    const payload = {
      name: form.name.trim(),
      threshold: form.threshold,
      discountRate: form.discountRate,
      pointRate: form.pointRate,
      sortNo: form.sortNo,
      status: form.status,
    }
    if (editDialog.editing) {
      await updateLevel(editDialog.id, payload)
      ElMessage.success('等级规则已更新，即时生效于后续结算')
    } else {
      await createLevel(payload)
      ElMessage.success('等级已新增')
    }
    editDialog.visible = false
    load()
  } finally {
    editDialog.saving = false
  }
}

// ---------- 停用 / 启用 ----------
async function toggleStatus(row: MemberLevelVO) {
  const to = row.status === 1 ? 0 : 1
  if (to === 0) {
    await ElMessageBox.confirm(
      `停用后【${row.name}】折扣率不再生效，收银按无折扣处理。确认停用？`,
      '停用等级',
      { type: 'warning' },
    )
  }
  await updateLevelStatus(row.id, to)
  ElMessage.success(to === 1 ? '已启用' : '已停用')
  load()
}
</script>

<template>
  <div class="page">
    <el-card>
      <template #header>
        <div class="card-head">
          <div>
            <span>会员等级规则</span>
            <span class="head-tip">预设：普通会员（无门槛）→ 银卡（满 1,000，98 折）→ 金卡（满 5,000，95 折，1.2 倍积分）</span>
          </div>
          <el-button v-if="canUpdate()" type="primary" @click="openCreate">新增等级</el-button>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="升级门槛按累计消费额判定（BR-MEM-06，默认只升不降）；折扣率与积分倍率即时生效于后续结算（BR-SALE-10 / BR-MEM-05）"
        class="table-alert"
      />

      <el-table v-loading="loading" :data="list" stripe>
        <el-table-column prop="sortNo" label="排序" width="70" align="center" />
        <el-table-column prop="name" label="等级名称" width="140" />
        <el-table-column label="升级门槛（累计消费）" width="170" align="right">
          <template #default="{ row }">
            {{ Number(row.threshold) === 0 ? '无门槛' : `满 ￥${money(row.threshold)}` }}
          </template>
        </el-table-column>
        <el-table-column label="折扣率" width="110" align="center">
          <template #default="{ row }">
            <el-tag v-if="Number(row.discountRate) < 1" type="primary" size="small">
              {{ Number(row.discountRate).toFixed(2) }} 折
            </el-tag>
            <span v-else class="no-discount">无折扣</span>
          </template>
        </el-table-column>
        <el-table-column label="积分倍率" width="100" align="center">
          <template #default="{ row }">× {{ Number(row.pointRate).toFixed(1) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近更新" width="160">
          <template #default="{ row }">{{ fmtTime(row.updateTime ?? row.createTime) }}</template>
        </el-table-column>
        <el-table-column prop="updateBy" label="更新人" width="100">
          <template #default="{ row }">{{ operatorName(row.updateBy ?? row.createBy) }}</template>
        </el-table-column>
        <el-table-column v-if="canUpdate()" label="操作" width="150" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row as MemberLevelVO)">编辑</el-button>
            <el-button
              link
              :type="row.status === 1 ? 'danger' : 'success'"
              @click="toggleStatus(row as MemberLevelVO)"
            >{{ row.status === 1 ? '停用' : '启用' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="table-tip">等级不提供删除（会员档案在用）；如需停用某等级折扣，请使用"停用"操作。</div>
    </el-card>

    <!-- 新增 / 编辑等级（FR-MEM-05） -->
    <el-dialog
      v-model="editDialog.visible"
      :title="editDialog.editing ? '编辑等级规则' : '新增等级'"
      width="460px"
      :close-on-click-modal="false"
    >
      <el-form label-width="110px" @submit.prevent>
        <el-form-item label="等级名称" required>
          <el-input v-model="editDialog.form.name" maxlength="30" placeholder="如：钻石会员" />
        </el-form-item>
        <el-form-item label="升级门槛（元）" required>
          <el-input-number
            v-model="editDialog.form.threshold"
            :min="0"
            :precision="2"
            :controls="false"
            placeholder="累计消费额达到该值升级"
            style="width: 100%"
          />
          <div class="form-tip">按累计消费额 = Σ实付 − Σ退款 自动升降（BR-MEM-06）</div>
        </el-form-item>
        <el-form-item label="折扣率" required>
          <el-input-number
            v-model="editDialog.form.discountRate"
            :min="0.01"
            :max="1"
            :step="0.01"
            :precision="2"
            :controls="false"
            style="width: 100%"
          />
          <div class="form-tip">(0, 1]，如 0.98 = 98 折；1 为无折扣（BR-SALE-10）</div>
        </el-form-item>
        <el-form-item label="积分倍率" required>
          <el-input-number
            v-model="editDialog.form.pointRate"
            :min="0.1"
            :max="99"
            :step="0.1"
            :precision="1"
            :controls="false"
            style="width: 100%"
          />
          <div class="form-tip">本次积分 = 实付 × 每元积分数 × 等级倍率（BR-MEM-05）</div>
        </el-form-item>
        <el-form-item label="排序" required>
          <el-input-number
            v-model="editDialog.form.sortNo"
            :min="0"
            :precision="0"
            :controls="false"
            style="width: 100%"
          />
          <div class="form-tip">按门槛升序展示，建议与门槛保持一致</div>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="editDialog.form.status" :disabled="editDialog.editing">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
          <div class="form-tip">停用请使用列表"停用"操作（编辑中不可同时改状态）</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="editDialog.saving" @click="saveLevel">保存</el-button>
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

    .head-tip {
      margin-left: 12px;
      font-size: 12px;
      font-weight: normal;
      color: #909399;
    }
  }

  .table-alert {
    margin-bottom: 12px;
  }

  .table-tip {
    margin-top: 10px;
    font-size: 12px;
    color: #909399;
  }

  .no-discount {
    color: #909399;
  }

  .form-tip {
    font-size: 12px;
    color: #909399;
    line-height: 1.4;
  }
}
</style>
