<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { listDicts, addDictItem, updateDictItem, type DictVO } from '@/api/system'

const auth = useAuthStore()

const loading = ref(false)
const dicts = ref<DictVO[]>([])
const activeType = ref('')

const activeDict = computed(() => dicts.value.find((d) => d.dictType === activeType.value))

async function load() {
  loading.value = true
  try {
    const data = await listDicts()
    dicts.value = data
    if (!activeType.value && data.length) {
      activeType.value = data[0].dictType
    }
  } finally {
    loading.value = false
  }
}

// ---------- 新增 / 编辑字典项 ----------
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref()
const saving = ref(false)
const form = reactive({ label: '', value: '', sortNo: 1, status: 1, remark: '' })

const rules = {
  label: [{ required: true, message: '请输入标签', trigger: 'blur' }],
  value: [
    { required: true, message: '请输入存储值', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9_]+$/, message: '建议使用大写英文代码（单据存代码，展示时翻译）', trigger: 'blur' },
  ],
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { label: '', value: '', sortNo: (activeDict.value?.items.length || 0) + 1, status: 1, remark: '' })
  dialogVisible.value = true
}

function openEdit(itemId: number) {
  const item = activeDict.value?.items.find((i) => i.id === itemId)
  if (!item) return
  editingId.value = itemId
  Object.assign(form, {
    label: item.label,
    value: item.value,
    sortNo: item.sortNo,
    status: item.status,
    remark: item.remark || '',
  })
  dialogVisible.value = true
}

async function save() {
  await formRef.value.validate()
  saving.value = true
  const payload = {
    label: form.label,
    value: form.value,
    sortNo: form.sortNo,
    status: form.status,
    remark: form.remark || undefined,
  }
  try {
    if (editingId.value) {
      await updateDictItem(editingId.value, payload)
      ElMessage.success('字典项已更新')
    } else {
      await addDictItem(activeType.value, payload)
      ElMessage.success('字典项已新增')
    }
    dialogVisible.value = false
    load()
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <el-card class="dict-card">
      <el-row :gutter="16">
        <!-- 左：字典类型 -->
        <el-col :span="6">
          <el-menu :default-active="activeType" @select="(key: string) => (activeType = key)">
            <el-menu-item v-for="dict in dicts" :key="dict.dictType" :index="dict.dictType">
              <span>{{ dict.dictName }}</span>
              <el-tag size="small" effect="plain" class="dict-type-tag">{{ dict.dictType }}</el-tag>
            </el-menu-item>
          </el-menu>
        </el-col>

        <!-- 右：字典项 -->
        <el-col :span="18">
          <div class="item-head">
            <div>
              <b>{{ activeDict?.dictName }}</b>
              <span class="hint">停用项不可被新单据选择，历史数据仍能正确显示</span>
            </div>
            <el-button
              v-if="auth.hasPerm('sys:dict:update')"
              type="primary"
              @click="openCreate"
            >
              <el-icon><Plus /></el-icon>新增字典项
            </el-button>
          </div>
          <el-table v-loading="loading" border :data="activeDict?.items || []" stripe>
            <el-table-column prop="label" label="标签" width="160" />
            <el-table-column label="存储值" width="160">
              <template #default="{ row }">
                <el-tag effect="plain" size="small">{{ row.value }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="sortNo" label="排序" width="80" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 1 ? 'success' : 'info'">
                  {{ row.status === 1 ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="备注" min-width="160">
              <template #default="{ row }">{{ row.remark || '—' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="auth.hasPerm('sys:dict:update')"
                  link
                  type="primary"
                  @click="openEdit(row.id)"
                >编辑</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-col>
      </el-row>
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑字典项' : `新增字典项：${activeDict?.dictName || ''}`"
      width="440px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="70px">
        <el-form-item label="标签" prop="label">
          <el-input v-model="form.label" maxlength="30" placeholder="显示名称，如 现金" />
        </el-form-item>
        <el-form-item label="存储值" prop="value">
          <el-input v-model="form.value" maxlength="30" placeholder="代码，如 CASH" />
        </el-form-item>
        <el-form-item label="排序号">
          <el-input-number v-model="form.sortNo" :min="0" :max="9999" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" maxlength="200" />
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
  .dict-card {
    :deep(.el-menu-item) {
      display: flex;
      justify-content: space-between;
      align-items: center;

      .dict-type-tag {
        font-family: monospace;
      }
    }
  }

  .item-head {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 12px;

    .hint {
      margin-left: 10px;
      color: #909399;
      font-size: 12px;
      font-weight: normal;
    }
  }
}
</style>
