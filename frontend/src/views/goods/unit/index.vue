<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import {
  listUnits,
  createUnit,
  updateUnit,
  type UnitVO,
} from '@/api/goods'

const auth = useAuthStore()

const loading = ref(false)
const list = ref<UnitVO[]>([])

async function load() {
  loading.value = true
  try {
    list.value = await listUnits()
  } finally {
    loading.value = false
  }
}

// ---------- 新增 / 编辑（FR-GOODS-08） ----------
const dialogVisible = ref(false)
const editing = ref<UnitVO | null>(null)
const formRef = ref<FormInstance>()
const saving = ref(false)
const form = reactive({ name: '', symbol: '', isWeight: 0, status: 1 })

const rules = {
  name: [{ required: true, message: '请输入单位名称', trigger: 'blur' }],
  symbol: [{ max: 10, message: '符号不能超过10个字符', trigger: 'blur' }],
}

function openCreate() {
  editing.value = null
  Object.assign(form, { name: '', symbol: '', isWeight: 0, status: 1 })
  dialogVisible.value = true
}

function openEdit(row: UnitVO) {
  editing.value = row
  Object.assign(form, {
    name: row.name,
    symbol: row.symbol ?? '',
    isWeight: row.isWeight,
    status: row.status,
  })
  dialogVisible.value = true
}

async function save() {
  await formRef.value?.validate()
  saving.value = true
  try {
    const payload = {
      name: form.name.trim(),
      symbol: form.symbol.trim() || undefined,
      isWeight: form.isWeight,
      status: form.status,
    }
    if (editing.value) {
      await updateUnit(editing.value.id, payload)
      ElMessage.success('单位已更新')
    } else {
      await createUnit(payload)
      ElMessage.success('单位已创建')
    }
    dialogVisible.value = false
    load()
  } catch {
    // 拦截器已提示（如名称重复、重量属性被商品引用）
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <el-card>
      <template #header>
        <div class="card-head">
          <span>计量单位（称重商品只能选择重量单位）</span>
          <el-button
            v-if="auth.hasPerm('sys:unit:create')"
            type="primary"
            @click="openCreate"
          >
            <el-icon><Plus /></el-icon>新增单位
          </el-button>
        </div>
      </template>

      <el-table v-loading="loading" :data="list" stripe>
        <el-table-column prop="name" label="单位名称" width="160" />
        <el-table-column label="符号" width="120">
          <template #default="{ row }">{{ row.symbol || '—' }}</template>
        </el-table-column>
        <el-table-column label="类型" width="140">
          <template #default="{ row }">
            <el-tag v-if="row.isWeight === 1" type="warning" size="small">重量单位</el-tag>
            <el-tag v-else type="info" size="small" effect="plain">计数单位</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" min-width="170" />
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="auth.hasPerm('sys:unit:update')"
              link
              type="primary"
              @click="openEdit(row as UnitVO)"
            >编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新增 / 编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editing ? `编辑单位：${editing.name}` : '新增单位'"
      width="440px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="88px">
        <el-form-item label="单位名称" prop="name">
          <el-input v-model="form.name" maxlength="10" placeholder="如 瓶/袋/kg，全局唯一" />
        </el-form-item>
        <el-form-item label="单位符号" prop="symbol">
          <el-input v-model="form.symbol" maxlength="10" placeholder="选填，如 kg" />
        </el-form-item>
        <el-form-item label="重量单位">
          <el-radio-group v-model="form.isWeight">
            <el-radio :value="1">是</el-radio>
            <el-radio :value="0">否</el-radio>
          </el-radio-group>
          <div class="field-tip">被商品引用后不可变更，否则保存将被拒绝</div>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
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

  .field-tip {
    font-size: 12px;
    color: #909399;
    line-height: 1.4;
  }
}
</style>
