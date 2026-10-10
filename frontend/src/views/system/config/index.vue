<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { listConfigs, updateConfig, type SysConfigVO } from '@/api/system'

const auth = useAuthStore()

const loading = ref(false)
const configs = ref<SysConfigVO[]>([])

/** 参数分组（FR-SYS-06：分组展示） */
const GROUP_LABELS: Record<string, string> = {
  basic: '基础信息',
  security: '安全策略',
  goods: '商品管理',
  inventory: '库存管理',
  purchase: '采购管理',
  sale: '销售管理',
  member: '会员管理',
  report: '报表统计',
}

const groups = computed(() => {
  const map = new Map<string, SysConfigVO[]>()
  for (const config of configs.value) {
    const list = map.get(config.configGroup) || []
    list.push(config)
    map.set(config.configGroup, list)
  }
  return [...map.entries()].map(([group, list]) => ({
    group,
    label: GROUP_LABELS[group] || group,
    list,
  }))
})

async function load() {
  loading.value = true
  try {
    configs.value = await listConfigs()
  } finally {
    loading.value = false
  }
}

// ---------- 编辑 ----------
const dialogVisible = ref(false)
const editing = ref<SysConfigVO | null>(null)
const formRef = ref()
const saving = ref(false)
const form = reactive({ configValue: '', remark: '', password: '' })

const rules = {
  configValue: [{ required: true, message: '请输入参数值', trigger: 'blur' }],
  password: [{ required: true, message: '请输入当前账号密码以确认操作', trigger: 'blur' }],
}

const VALUE_TYPE_PLACEHOLDER: Record<string, string> = {
  NUMBER: '数字，如 5 或 0.7',
  BOOLEAN: 'true / false',
  TIME: 'HH:mm，如 00:00',
  STRING: '文本',
}

// 类型列的中文标签，避免表格出现 NUMBER / BOOLEAN 等英文枚举
const VALUE_TYPE_LABEL: Record<string, string> = {
  NUMBER: '数字',
  BOOLEAN: '布尔',
  TIME: '时间',
  STRING: '文本',
}

function valueTypeLabel(v: string) {
  return VALUE_TYPE_LABEL[v] ?? v
}

function openEdit(row: SysConfigVO) {
  editing.value = row
  form.configValue = row.configValue
  form.remark = row.remark || ''
  form.password = ''
  dialogVisible.value = true
}

async function save() {
  await formRef.value.validate()
  if (!editing.value) return
  saving.value = true
  try {
    await updateConfig(editing.value.configKey, form.configValue, form.remark, form.password)
    ElMessage.success(
      editing.value.instantEffect === 1 ? '参数已保存并即时生效' : '参数已保存，重启后生效',
    )
    dialogVisible.value = false
    form.password = ''
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
  <div v-loading="loading" class="page">
    <el-card v-for="group in groups" :key="group.group" class="group-card">
      <template #header>{{ group.label }}</template>
      <el-table :data="group.list" stripe>
        <el-table-column prop="configName" label="参数名称" width="180" />
        <el-table-column prop="configKey" label="参数键" width="200">
          <template #default="{ row }">
            <code class="config-key">{{ row.configKey }}</code>
          </template>
        </el-table-column>
        <el-table-column label="参数值" min-width="140">
          <template #default="{ row }">
            <el-tag v-if="row.valueType === 'BOOLEAN'" :type="row.configValue === 'true' ? 'success' : 'danger'" size="small">
              {{ row.configValue }}
            </el-tag>
            <code v-else class="config-value">{{ row.configValue }}</code>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">{{ valueTypeLabel(row.valueType) }}</template>
        </el-table-column>
        <el-table-column label="说明" min-width="220">
          <template #default="{ row }">{{ row.remark || '—' }}</template>
        </el-table-column>
        <el-table-column label="生效方式" width="90">
          <template #default="{ row }">
            <el-tag :type="row.instantEffect === 1 ? 'success' : 'info'" size="small" effect="plain">
              {{ row.instantEffect === 1 ? '即时' : '重启' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="auth.hasPerm('sys:config:update') && row.editable === 1"
              link
              type="primary"
              @click="openEdit(row as SysConfigVO)"
            >修改</el-button>
            <el-tooltip v-else content="只读参数" placement="top">
              <span class="readonly">只读</span>
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="`修改参数：${editing?.configName || ''}`" width="460px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="70px">
        <el-form-item label="参数键">
          <code class="config-key">{{ editing?.configKey }}</code>
        </el-form-item>
        <el-form-item label="参数值" prop="configValue">
          <el-input
            v-if="editing?.valueType === 'BOOLEAN'"
            v-model="form.configValue"
            placeholder="true / false"
          />
          <el-input v-else v-model="form.configValue" :placeholder="VALUE_TYPE_PLACEHOLDER[editing?.valueType || 'STRING']" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.remark" type="textarea" maxlength="200" />
        </el-form-item>
        <el-form-item label="确认密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            maxlength="50"
            placeholder="当前账号密码（敏感操作二次确认）"
          />
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
  .group-card {
    margin-bottom: 12px;
  }

  .config-key {
    color: #909399;
    font-size: 12px;
    font-family: monospace;
  }

  .config-value {
    color: #303133;
    font-family: monospace;
  }

  .readonly {
    color: #c0c4cc;
    font-size: 13px;
    cursor: not-allowed;
  }
}
</style>
