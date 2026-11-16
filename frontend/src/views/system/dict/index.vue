<script setup lang="ts">
import { notify } from '@/utils/notify'
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
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
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
      notify.success('字典项已更新')
    } else {
      await addDictItem(activeType.value, payload)
      notify.success('字典项已新增')
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
    <UCard class="dict-card">
      <AppRow :gutter="16">
        <!-- 左：字典类型 -->
        <AppCol :span="6">
          <nav class="flex flex-col gap-1">
            <button
              v-for="dict in dicts"
              :key="dict.dictType"
              type="button"
              class="flex w-full items-center justify-between gap-2 rounded-md px-3 py-2 text-left text-sm hover:bg-elevated"
              :class="activeType === dict.dictType ? 'bg-elevated font-medium text-primary' : 'text-default'"
              @click="activeType = dict.dictType"
            >
              <span>{{ dict.dictName }}</span>
              <UBadge size="sm" variant="outline" class="dict-type-tag">{{ dict.dictType }}</UBadge>
            </button>
          </nav>
        </AppCol>

        <!-- 右：字典项 -->
        <AppCol :span="18">
          <div class="item-head">
            <div>
              <b>{{ activeDict?.dictName }}</b>
              <span class="hint">停用项不可被新单据选择，历史数据仍能正确显示</span>
            </div>
            <UButton
              v-if="auth.hasPerm('sys:dict:update')"
              icon="i-lucide-plus"
              label="新增字典项"
              @click="openCreate"
            />
          </div>
          <AppTable :data="activeDict?.items || []" :loading="loading" border stripe>
            <AppTableColumn prop="label" label="标签" width="160" />
            <AppTableColumn label="存储值" width="160">
              <template #default="{ row }">
                <UBadge variant="outline" size="sm">{{ row.value }}</UBadge>
              </template>
            </AppTableColumn>
            <AppTableColumn prop="sortNo" label="排序" width="80" />
            <AppTableColumn label="状态" width="90">
              <template #default="{ row }">
                <UBadge :color="row.status === 1 ? 'success' : 'neutral'" variant="soft">
                  {{ row.status === 1 ? '启用' : '停用' }}
                </UBadge>
              </template>
            </AppTableColumn>
            <AppTableColumn label="备注" min-width="160">
              <template #default="{ row }">{{ row.remark || '—' }}</template>
            </AppTableColumn>
            <AppTableColumn label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <UButton
                  v-if="auth.hasPerm('sys:dict:update')"
                  variant="link"
                  size="sm"
                  label="编辑"
                  @click="openEdit(row.id)"
                />
              </template>
            </AppTableColumn>
          </AppTable>
        </AppCol>
      </AppRow>
    </UCard>

    <AppModal
      v-model="dialogVisible"
      :title="editingId ? '编辑字典项' : `新增字典项：${activeDict?.dictName || ''}`"
      width="440px"
    >
      <AppForm ref="formRef" :model="form" :rules="rules" label-width="70px">
        <AppFormItem label="标签" prop="label">
          <AppInput v-model="form.label" maxlength="30" placeholder="显示名称，如 现金" />
        </AppFormItem>
        <AppFormItem label="存储值" prop="value">
          <AppInput v-model="form.value" maxlength="30" placeholder="代码，如 CASH" />
        </AppFormItem>
        <AppFormItem label="排序号">
          <AppInputNumber v-model="form.sortNo" :min="0" :max="9999" />
        </AppFormItem>
        <AppFormItem label="状态">
          <AppRadioGroup v-model="form.status">
            <AppRadio :value="1">启用</AppRadio>
            <AppRadio :value="0">停用</AppRadio>
          </AppRadioGroup>
        </AppFormItem>
        <AppFormItem label="备注">
          <AppInput v-model="form.remark" type="textarea" maxlength="200" />
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
  .dict-type-tag {
    font-family: monospace;
  }

  .item-head {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 12px;

    .hint {
      margin-left: 10px;
      color: var(--el-text-color-secondary);
      font-size: 12px;
      font-weight: normal;
    }
  }
}
</style>
