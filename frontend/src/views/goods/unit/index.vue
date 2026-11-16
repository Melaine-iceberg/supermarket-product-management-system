<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { notify } from '@/utils/notify'
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
const formRef = ref<{ validate: () => Promise<boolean> }>()
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
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
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
      notify.success('单位已更新')
    } else {
      await createUnit(payload)
      notify.success('单位已创建')
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
    <UCard>
      <template #header>
        <div class="flex items-center justify-between gap-2">
          <span class="font-medium text-default">计量单位（称重商品只能选择重量单位）</span>
          <UButton
            v-if="auth.hasPerm('sys:unit:create')"
            icon="i-lucide-plus"
            label="新增单位"
            @click="openCreate"
          />
        </div>
      </template>

      <AppTable :data="list" :loading="loading" border stripe>
        <AppTableColumn prop="name" label="单位名称" width="160" />

        <AppTableColumn label="符号" width="120">
          <template #default="{ row }">{{ row.symbol || '—' }}</template>
        </AppTableColumn>

        <AppTableColumn label="类型" width="140">
          <template #default="{ row }">
            <UBadge
              v-if="row.isWeight === 1"
              color="warning"
              variant="soft"
              size="sm"
            >
              重量单位
            </UBadge>
            <UBadge v-else color="neutral" variant="outline" size="sm">计数单位</UBadge>
          </template>
        </AppTableColumn>

        <AppTableColumn label="状态" width="100">
          <template #default="{ row }">
            <UBadge :color="row.status === 1 ? 'success' : 'error'" variant="soft" size="sm">
              {{ row.status === 1 ? '启用' : '停用' }}
            </UBadge>
          </template>
        </AppTableColumn>

        <AppTableColumn prop="createTime" label="创建时间" min-width="170" />

        <AppTableColumn label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <UButton
              v-if="auth.hasPerm('sys:unit:update')"
              variant="link"
              size="sm"
              label="编辑"
              @click="openEdit(row as UnitVO)"
            />
          </template>
        </AppTableColumn>
      </AppTable>
    </UCard>

    <!-- 新增 / 编辑对话框 -->
    <AppModal
      v-model="dialogVisible"
      :title="editing ? `编辑单位：${editing.name}` : '新增单位'"
      width="440px"
    >
      <AppForm ref="formRef" :model="form" :rules="rules" label-width="88px">
        <AppFormItem label="单位名称" prop="name">
          <AppInput v-model="form.name" maxlength="10" placeholder="如 瓶/袋/kg，全局唯一" />
        </AppFormItem>
        <AppFormItem label="单位符号" prop="symbol">
          <AppInput v-model="form.symbol" maxlength="10" placeholder="选填，如 kg" />
        </AppFormItem>
        <AppFormItem label="重量单位">
          <AppRadioGroup v-model="form.isWeight">
            <AppRadio :value="1">是</AppRadio>
            <AppRadio :value="0">否</AppRadio>
          </AppRadioGroup>
          <div class="mt-1 text-xs leading-snug text-muted">
            被商品引用后不可变更，否则保存将被拒绝
          </div>
        </AppFormItem>
        <AppFormItem label="状态">
          <AppRadioGroup v-model="form.status">
            <AppRadio :value="1">启用</AppRadio>
            <AppRadio :value="0">停用</AppRadio>
          </AppRadioGroup>
        </AppFormItem>
      </AppForm>

      <template #footer>
        <UButton color="neutral" variant="outline" label="取消" @click="dialogVisible = false" />
        <UButton :loading="saving" label="保存" @click="save" />
      </template>
    </AppModal>
  </div>
</template>
