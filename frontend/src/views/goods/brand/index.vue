<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance } from 'element-plus'
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
const formRef = ref<FormInstance>()
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
  await formRef.value?.validate()
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
      ElMessage.success('品牌已更新')
    } else {
      await createBrand(payload)
      ElMessage.success('品牌已创建')
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
    <el-card class="search-card">
      <el-form inline @submit.prevent>
        <el-form-item label="品牌名称">
          <el-input
            v-model="keyword"
            placeholder="名称关键字过滤（实时生效）"
            clearable
            style="width: 220px"
          />
        </el-form-item>
      </el-form>
    </el-card>

    <el-card>
      <template #header>
        <div class="card-head">
          <span>品牌列表</span>
          <el-button
            v-if="auth.hasPerm('sys:brand:create')"
            type="primary"
            @click="openCreate"
          >
            <el-icon><Plus /></el-icon>新增品牌
          </el-button>
        </div>
      </template>

      <el-table v-loading="loading" :data="list" stripe>
        <el-table-column prop="name" label="品牌名称" min-width="160" />
        <el-table-column label="首字母" width="90">
          <template #default="{ row }">{{ row.firstLetter || '—' }}</template>
        </el-table-column>
        <el-table-column label="Logo" width="90">
          <template #default="{ row }">{{ row.logo ? '已设置' : '—' }}</template>
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
              v-if="auth.hasPerm('sys:brand:update')"
              link
              type="primary"
              @click="openEdit(row as BrandVO)"
            >编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新增 / 编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editing ? `编辑品牌：${editing.name}` : '新增品牌'"
      width="440px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="88px">
        <el-form-item label="品牌名称" prop="name">
          <el-input v-model="form.name" maxlength="30" placeholder="全局唯一" />
        </el-form-item>
        <el-form-item label="首字母" prop="firstLetter">
          <el-input v-model="form.firstLetter" maxlength="2" placeholder="拼音首字母，选填" />
        </el-form-item>
        <el-form-item label="Logo 路径">
          <el-input v-model="form.logo" maxlength="255" placeholder="图片相对路径，选填" />
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
  .search-card {
    margin-bottom: 12px;

    :deep(.el-card__body) {
      padding-bottom: 2px;
    }
  }

  .card-head {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
}
</style>
