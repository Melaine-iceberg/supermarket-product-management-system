<script setup lang="ts">
import { notify } from '@/utils/notify'
import { confirm, prompt } from '@/utils/confirm'
import { onMounted, reactive, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import {
  pageUsers,
  createUser,
  updateUser,
  updateUserStatus,
  resetUserPassword,
  listRoles,
  type SysUserVO,
  type SysRoleVO,
} from '@/api/system'

const auth = useAuthStore()

// ---------- 列表查询 ----------
const loading = ref(false)
const list = ref<SysUserVO[]>([])
const total = ref(0)
const query = reactive({ username: '', status: '' as number | string, page: 1, size: 10 })

async function load() {
  loading.value = true
  try {
    const data = await pageUsers({
      username: query.username || undefined,
      status: query.status === '' ? undefined : query.status,
      page: query.page,
      size: query.size,
    })
    list.value = data.list
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function resetQuery() {
  query.username = ''
  query.status = ''
  query.page = 1
  load()
}

// ---------- 新增 / 编辑 ----------
const roles = ref<SysRoleVO[]>([])
const dialogVisible = ref(false)
const editing = ref<SysUserVO | null>(null)
const formRef = ref()
const saving = ref(false)
const form = reactive({
  username: '',
  realName: '',
  phone: '',
  remark: '',
  roleIds: [] as number[],
})

const createRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_]{3,30}$/, message: '3~30 位字母、数字或下划线', trigger: 'blur' },
  ],
  realName: [{ required: true, message: '请输入真实姓名', trigger: 'blur' }],
  phone: [{ pattern: /^$|^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }],
}

const editRules = {
  realName: [{ required: true, message: '请输入真实姓名', trigger: 'blur' }],
  phone: [{ pattern: /^$|^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }],
}

function openCreate() {
  editing.value = null
  Object.assign(form, { username: '', realName: '', phone: '', remark: '', roleIds: [] })
  dialogVisible.value = true
}

function openEdit(row: SysUserVO) {
  editing.value = row
  Object.assign(form, {
    username: row.username,
    realName: row.realName,
    phone: row.phone || '',
    remark: row.remark || '',
    roleIds: [...row.roleIds],
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
    if (editing.value) {
      await updateUser(editing.value.id, {
        realName: form.realName,
        phone: form.phone || undefined,
        remark: form.remark || undefined,
        roleIds: form.roleIds,
      })
      notify.success('用户已更新')
    } else {
      const created = await createUser({
        username: form.username,
        realName: form.realName,
        phone: form.phone || undefined,
        remark: form.remark || undefined,
        roleIds: form.roleIds,
      })
      showInitialPassword(created.initialPassword)
    }
    dialogVisible.value = false
    load()
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

// 新增成功后展示一次性初始密码（BR-SYS-02）
const pwdDialog = reactive({ visible: false, password: '' })

function showInitialPassword(password: string) {
  pwdDialog.password = password
  pwdDialog.visible = true
}

async function copyPassword() {
  await navigator.clipboard.writeText(pwdDialog.password)
  notify.success('已复制到剪贴板')
}

// ---------- 停用 / 启用 ----------
async function toggleStatus(row: SysUserVO) {
  const disabling = row.status === 1
  const tip = disabling
    ? `停用后 ${row.username} 的登录将立即失效，确定停用？`
    : `确定启用账号 ${row.username}？`
  await confirm(tip, disabling ? '停用用户' : '启用用户', { type: 'warning' })
  await updateUserStatus(row.id, disabling ? 0 : 1)
  notify.success(disabling ? '已停用' : '已启用')
  load()
}

// ---------- 重置密码 ----------
const resetting = ref(false)

async function handleResetPwd(row: SysUserVO) {
  const { value: confirmPwd } = await prompt(
    `将为 ${row.realName}（${row.username}）生成随机初始密码，并要求其首次登录后修改。请输入您的登录密码确认：`,
    '重置密码（敏感操作二次确认）',
    {
      type: 'warning',
      inputType: 'password',
      inputPlaceholder: '当前账号密码',
      inputValidator: (v: string) => (v ? true : '密码不能为空'),
    },
  )
  resetting.value = true
  try {
    const password = await resetUserPassword(row.id, confirmPwd)
    showInitialPassword(password)
  } finally {
    resetting.value = false
  }
}

onMounted(() => {
  load()
  listRoles().then((data) => (roles.value = data))
})
</script>

<template>
  <div class="page">
    <!-- 搜索栏 -->
    <UCard class="search-card" :ui="{ body: 'pb-2' }">
      <AppForm inline @submit.prevent>
        <AppFormItem label="用户名">
          <AppInput v-model="query.username" placeholder="用户名模糊查询" clearable />
        </AppFormItem>
        <AppFormItem label="状态">
          <AppSelect v-model="query.status" clearable placeholder="全部" style="width: 110px">
            <AppOption label="启用" :value="1" />
            <AppOption label="停用" :value="0" />
          </AppSelect>
        </AppFormItem>
        <AppFormItem>
          <UButton label="查询" @click="query.page = 1; load()" />
          <UButton color="neutral" variant="outline" label="重置" @click="resetQuery" />
        </AppFormItem>
      </AppForm>
    </UCard>

    <UCard>
      <template #header>
        <div class="card-head">
          <span>用户列表（用户不可删除，仅可停用）</span>
          <UButton
            v-if="auth.hasPerm('sys:user:create')"
            icon="i-lucide-plus"
            label="新增用户"
            @click="openCreate"
          />
        </div>
      </template>

      <AppTable :data="list" :loading="loading" border stripe>
        <AppTableColumn prop="username" label="用户名" width="110" />
        <AppTableColumn prop="realName" label="真实姓名" width="100" />
        <AppTableColumn prop="phone" label="手机号" width="130">
          <template #default="{ row }">{{ row.phone || '—' }}</template>
        </AppTableColumn>
        <AppTableColumn label="角色" min-width="140">
          <template #default="{ row }">
            <UBadge
              v-for="name in row.roleNames"
              :key="name"
              size="sm"
              variant="soft"
              class="role-tag"
            >
              {{ name }}
            </UBadge>
            <span v-if="!row.roleNames.length">—</span>
          </template>
        </AppTableColumn>
        <AppTableColumn label="状态" width="80">
          <template #default="{ row }">
            <UBadge :color="row.status === 1 ? 'success' : 'error'" variant="soft">
              {{ row.status === 1 ? '启用' : '停用' }}
            </UBadge>
          </template>
        </AppTableColumn>
        <AppTableColumn label="待改密" width="80">
          <template #default="{ row }">
            <UBadge v-if="row.mustChangePwd" color="warning" size="sm" variant="soft">是</UBadge>
            <span v-else>—</span>
          </template>
        </AppTableColumn>
        <AppTableColumn prop="lastLoginTime" label="最后登录" width="160">
          <template #default="{ row }">{{ row.lastLoginTime || '从未登录' }}</template>
        </AppTableColumn>
        <AppTableColumn label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <UButton
              v-if="auth.hasPerm('sys:user:update')"
              variant="link"
              size="sm"
              label="编辑"
              @click="openEdit(row as SysUserVO)"
            />
            <UButton
              v-if="auth.hasPerm('sys:user:status')"
              variant="link"
              size="sm"
              :color="row.status === 1 ? 'error' : 'success'"
              :label="row.status === 1 ? '停用' : '启用'"
              @click="toggleStatus(row as SysUserVO)"
            />
            <UButton
              v-if="auth.hasPerm('sys:user:reset-password')"
              variant="link"
              size="sm"
              color="warning"
              label="重置密码"
              :loading="resetting"
              @click="handleResetPwd(row as SysUserVO)"
            />
          </template>
        </AppTableColumn>
      </AppTable>

      <AppPagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @change="load"
      />
    </UCard>

    <!-- 新增 / 编辑对话框 -->
    <AppModal
      v-model="dialogVisible"
      :title="editing ? `编辑用户：${editing.username}` : '新增用户'"
      width="480px"
    >
      <UAlert
        v-if="!editing"
        color="info"
        icon="i-lucide-info"
        title="初始密码由系统随机生成，用户首次登录后须修改"
        class="dialog-alert"
      />
      <AppForm ref="formRef" :model="form" :rules="editing ? editRules : createRules" label-width="80px">
        <AppFormItem label="用户名" prop="username">
          <AppInput v-model="form.username" :disabled="!!editing" placeholder="登录账号，创建后不可修改" />
        </AppFormItem>
        <AppFormItem label="真实姓名" prop="realName">
          <AppInput v-model="form.realName" maxlength="30" />
        </AppFormItem>
        <AppFormItem label="手机号" prop="phone">
          <AppInput v-model="form.phone" maxlength="11" placeholder="选填" />
        </AppFormItem>
        <AppFormItem label="角色" prop="roleIds">
          <AppSelect v-model="form.roleIds" multiple placeholder="可多选" style="width: 100%">
            <AppOption
              v-for="role in roles.filter((r) => r.status === 1)"
              :key="role.id"
              :label="role.roleName"
              :value="role.id"
              :disabled="role.roleCode === 'SUPER_ADMIN' && editing?.id !== 1"
            />
          </AppSelect>
          <div class="role-tip">超级管理员角色仅可授予初始账号（BR-SYS-03）</div>
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

    <!-- 初始密码展示 -->
    <AppModal v-model="pwdDialog.visible" title="一次性初始密码" width="420px">
      <UAlert
        color="warning"
        icon="i-lucide-triangle-alert"
        title="请立即复制并安全告知该用户，密码仅展示一次"
      />
      <div class="pwd-box">{{ pwdDialog.password }}</div>
      <template #footer>
        <UButton label="复制密码" @click="copyPassword" />
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

  .role-tag {
    margin: 2px 4px 2px 0;
  }

  .role-tip {
    margin-top: 4px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
    line-height: 1.4;
  }

  .pager {
    margin-top: 14px;
    justify-content: flex-end;
  }

  .dialog-alert {
    margin-bottom: 14px;
  }

  .pwd-box {
    margin-top: 14px;
    padding: 14px;
    text-align: center;
    font-size: 22px;
    letter-spacing: 2px;
    font-family: monospace;
    background: var(--el-bg-color-page);
    border-radius: 6px;
  }
}
</style>
