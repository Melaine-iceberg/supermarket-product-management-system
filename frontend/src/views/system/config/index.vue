<script setup lang="ts">
import { notify } from '@/utils/notify'
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
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  if (!editing.value) return
  saving.value = true
  try {
    await updateConfig(editing.value.configKey, form.configValue, form.remark, form.password)
    notify.success(
      editing.value.instantEffect === 1
        ? '参数已保存并即时生效'
        : '参数已保存，最迟 60 秒内生效（无需重启）',
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
    <UCard v-for="group in groups" :key="group.group" class="group-card">
      <template #header>{{ group.label }}</template>
      <AppTable :data="group.list" stripe>
        <AppTableColumn prop="configName" label="参数名称" width="180" />
        <AppTableColumn prop="configKey" label="参数键" width="200">
          <template #default="{ row }">
            <code class="config-key">{{ row.configKey }}</code>
          </template>
        </AppTableColumn>
        <AppTableColumn label="参数值" min-width="140">
          <template #default="{ row }">
            <UBadge
              v-if="row.valueType === 'BOOLEAN'"
              :color="row.configValue === 'true' ? 'success' : 'error'"
              size="sm"
              variant="soft"
            >
              {{ row.configValue }}
            </UBadge>
            <code v-else class="config-value">{{ row.configValue }}</code>
          </template>
        </AppTableColumn>
        <AppTableColumn label="类型" width="90">
          <template #default="{ row }">{{ valueTypeLabel(row.valueType) }}</template>
        </AppTableColumn>
        <AppTableColumn label="说明" min-width="220">
          <template #default="{ row }">{{ row.remark || '—' }}</template>
        </AppTableColumn>
        <AppTableColumn label="生效方式" width="110">
          <template #default="{ row }">
            <UTooltip
              :text="
                row.instantEffect === 1
                  ? '保存后立即清除参数缓存，后续读取即用新值'
                  : '保存后不强制刷新缓存，最迟 60 秒（缓存周期）内生效，无需重启系统'
              "
            >
              <UBadge
                :color="row.instantEffect === 1 ? 'success' : 'neutral'"
                size="sm"
                variant="outline"
              >
                {{ row.instantEffect === 1 ? '即时' : '延迟' }}
              </UBadge>
            </UTooltip>
          </template>
        </AppTableColumn>
        <AppTableColumn label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <UButton
              v-if="auth.hasPerm('sys:config:update') && row.editable === 1"
              variant="link"
              size="sm"
              label="修改"
              @click="openEdit(row as SysConfigVO)"
            />
            <UTooltip v-else text="只读参数">
              <span class="readonly">只读</span>
            </UTooltip>
          </template>
        </AppTableColumn>
      </AppTable>
    </UCard>

    <AppModal v-model="dialogVisible" :title="`修改参数：${editing?.configName || ''}`" width="460px">
      <AppForm ref="formRef" :model="form" :rules="rules" label-width="70px">
        <AppFormItem label="参数键">
          <code class="config-key">{{ editing?.configKey }}</code>
        </AppFormItem>
        <AppFormItem label="参数值" prop="configValue">
          <AppInput
            v-if="editing?.valueType === 'BOOLEAN'"
            v-model="form.configValue"
            placeholder="true / false"
          />
          <AppInput
            v-else
            v-model="form.configValue"
            :placeholder="VALUE_TYPE_PLACEHOLDER[editing?.valueType || 'STRING']"
          />
        </AppFormItem>
        <AppFormItem label="说明">
          <AppInput v-model="form.remark" type="textarea" maxlength="200" />
        </AppFormItem>
        <AppFormItem label="确认密码" prop="password">
          <AppInput
            v-model="form.password"
            type="password"
            show-password
            maxlength="50"
            placeholder="当前账号密码（敏感操作二次确认）"
          />
        </AppFormItem>
      </AppForm>
      <template #footer>
        <UButton color="neutral" variant="outline" label="取消" @click="dialogVisible = false" />
        <UButton :loading="saving" label="保存" @click="save" />
      </template>
    </AppModal>
  </div>
</template>

<style scoped lang="scss">
.page {
  .group-card {
    margin-bottom: 12px;
  }

  .config-key {
    color: var(--el-text-color-secondary);
    font-size: 12px;
    font-family: monospace;
  }

  .config-value {
    color: var(--el-text-color-primary);
    font-family: monospace;
  }

  .readonly {
    color: var(--el-text-color-placeholder);
    font-size: 13px;
    cursor: not-allowed;
  }
}
</style>
