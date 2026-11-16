<script setup lang="ts">
import { notify } from '@/utils/notify'
import { computed, onMounted, reactive, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import {
  listBrands,
  createBrand,
  updateBrand,
  type BrandVO,
} from '@/api/goods'

const auth = useAuthStore()

const loading = ref(false)
const all = ref<BrandVO[]>([])
const keyword = ref('')

/** 前端关键字过滤（品牌量级小，接口无分页） */
const list = computed(() => {
  const kw = keyword.value.trim()
  return kw ? all.value.filter((b) => b.name.includes(kw)) : all.value
})

async function load() {
  loading.value = true
  try {
    all.value = await listBrands()
  } finally {
    loading.value = false
  }
}

// ---------- 新增 / 编辑（FR-GOODS-13） ----------
const dialogVisible = ref(false)
const editing = ref<BrandVO | null>(null)
const formRef = ref()
const saving = ref(false)
const form = reactive({ name: '', firstLetter: '', logo: '', status: 1 })

const rules = {
  name: [{ required: true, message: '请输入品牌名称', trigger: 'blur' }],
  firstLetter: [
    { pattern: /^$|^[A-Za-z]{1,2}$/, message: '首字母须为1~2位英文字母', trigger: 'blur' },
  ],
}

function openCreate() {
  editing.value = null
  Object.assign(form, { name: '', firstLetter: '', logo: '', status: 1 })
  dialogVisible.value = true
}

function openEdit(row: BrandVO) {
  editing.value = row
  Object.assign(form, {
    name: row.name,
    firstLetter: row.firstLetter ?? '',
    logo: row.logo ?? '',
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
      firstLetter: form.firstLetter.trim().toUpperCase() || undefined,
      logo: form.logo.trim() || undefined,
      status: form.status,
    }
    if (editing.value) {
      await updateBrand(editing.value.id, payload)
      notify.success('品牌已更新')
    } else {
      await createBrand(payload)
      notify.success('品牌已创建')
    }
    dialogVisible.value = false
    load()
  } catch {
    // 拦截器已提示（如名称重复）
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <UCard class="search-card" :ui="{ body: 'pb-0.5' }">
      <AppForm inline @submit.prevent>
        <AppFormItem label="品牌名称">
          <AppInput
            v-model="keyword"
            placeholder="名称关键字过滤（实时生效）"
            clearable
            style="width: 220px"
          />
        </AppFormItem>
      </AppForm>
    </UCard>

    <UCard>
      <template #header>
        <div class="card-head">
          <span>品牌列表</span>
          <UButton
            v-if="auth.hasPerm('sys:brand:create')"
            icon="i-lucide-plus"
            label="新增品牌"
            @click="openCreate"
          />
        </div>
      </template>

      <AppTable :data="list" :loading="loading" border stripe>
        <AppTableColumn prop="name" label="品牌名称" min-width="160" />
        <AppTableColumn label="首字母" width="90">
          <template #default="{ row }">{{ row.firstLetter || '—' }}</template>
        </AppTableColumn>
        <AppTableColumn label="品牌标识" width="90">
          <template #default="{ row }">{{ row.logo ? '已设置' : '—' }}</template>
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
              v-if="auth.hasPerm('sys:brand:update')"
              variant="link"
              size="sm"
              label="编辑"
              @click="openEdit(row as BrandVO)"
            />
          </template>
        </AppTableColumn>
      </AppTable>
    </UCard>

    <!-- 新增 / 编辑对话框 -->
    <AppModal
      v-model="dialogVisible"
      :title="editing ? `编辑品牌：${editing.name}` : '新增品牌'"
      width="440px"
    >
      <AppForm ref="formRef" :model="form" :rules="rules" label-width="88px">
        <AppFormItem label="品牌名称" prop="name">
          <AppInput v-model="form.name" maxlength="30" placeholder="全局唯一" />
        </AppFormItem>
        <AppFormItem label="首字母" prop="firstLetter">
          <AppInput v-model="form.firstLetter" maxlength="2" placeholder="拼音首字母，选填" />
        </AppFormItem>
        <AppFormItem label="标识图片路径">
          <AppInput v-model="form.logo" maxlength="255" placeholder="图片相对路径，选填" />
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
}
</style>
