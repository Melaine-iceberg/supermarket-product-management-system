<script setup lang="ts">
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
  await formRef.value.validate()
  saving.value = true
  try {
    if (editing.value) {
      await updateUser(editing.value.id, {
        realName: form.realName,
        phone: form.phone || undefined,
        remark: form.remark || undefined,
        roleIds: form.roleIds,
      })
      ElMessage.success('用户已更新')
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
  ElMessage.success('已复制到剪贴板')
}

// ---------- 停用 / 启用 ----------
async function toggleStatus(row: SysUserVO) {
  const disabling = row.status === 1
  const tip = disabling
    ? `停用后 ${row.username} 的登录将立即失效，确定停用？`
    : `确定启用账号 ${row.username}？`
  await ElMessageBox.confirm(tip, disabling ? '停用用户' : '启用用户', { type: 'warning' })
  await updateUserStatus(row.id, disabling ? 0 : 1)
  ElMessage.success(disabling ? '已停用' : '已启用')
  load()
}

// ---------- 重置密码 ----------
const resetting = ref(false)

async function handleResetPwd(row: SysUserVO) {
  await ElMessageBox.confirm(
    `将为 ${row.realName}（${row.username}）生成随机初始密码，并要求其首次登录后修改，确定继续？`,
    '重置密码',
    { type: 'warning' },
  )
  resetting.value = true
  try {
    const password = await resetUserPassword(row.id)
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
    <el-card class="search-card">
      <el-form inline @submit.prevent>
        <el-form-item label="用户名">
          <el-input v-model="query.username" placeholder="用户名模糊查询" clearable />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 110px">
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="query.page = 1; load()">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card>
      <template #header>
        <div class="card-head">
          <span>用户列表（用户不可删除，仅可停用）</span>
          <el-button
            v-if="auth.hasPerm('sys:user:create')"
            type="primary"
            @click="openCreate"
          >
            <el-icon><Plus /></el-icon>新增用户
          </el-button>
        </div>
      </template>

      <el-table v-loading="loading" :data="list" stripe>
        <el-table-column prop="username" label="用户名" width="110" />
        <el-table-column prop="realName" label="真实姓名" width="100" />
        <el-table-column prop="phone" label="手机号" width="130">
          <template #default="{ row }">{{ row.phone || '—' }}</template>
        </el-table-column>
        <el-table-column label="角色" min-width="140">
          <template #default="{ row }">
            <el-tag v-for="name in row.roleNames" :key="name" size="small" class="role-tag">
              {{ name }}
            </el-tag>
            <span v-if="!row.roleNames.length">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="待改密" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.mustChangePwd" type="warning" size="small">是</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column prop="lastLoginTime" label="最后登录" width="160">
          <template #default="{ row }">{{ row.lastLoginTime || '从未登录' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="auth.hasPerm('sys:user:update')"
              link
              type="primary"
              @click="openEdit(row as SysUserVO)"
            >编辑</el-button>
            <el-button
              v-if="auth.hasPerm('sys:user:status')"
              link
              :type="row.status === 1 ? 'danger' : 'success'"
              @click="toggleStatus(row as SysUserVO)"
            >{{ row.status === 1 ? '停用' : '启用' }}</el-button>
            <el-button
              v-if="auth.hasPerm('sys:user:reset-password')"
              link
              type="warning"
              :loading="resetting"
              @click="handleResetPwd(row as SysUserVO)"
            >重置密码</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @change="load"
      />
    </el-card>

    <!-- 新增 / 编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editing ? `编辑用户：${editing.username}` : '新增用户'"
      width="480px"
    >
      <el-alert
        v-if="!editing"
        type="info"
        :closable="false"
        show-icon
        title="初始密码由系统随机生成，用户首次登录后须修改"
        class="dialog-alert"
      />
      <el-form ref="formRef" :model="form" :rules="editing ? editRules : createRules" label-width="80px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" :disabled="!!editing" placeholder="登录账号，创建后不可修改" />
        </el-form-item>
        <el-form-item label="真实姓名" prop="realName">
          <el-input v-model="form.realName" maxlength="30" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="form.phone" maxlength="11" placeholder="选填" />
        </el-form-item>
        <el-form-item label="角色" prop="roleIds">
          <el-select v-model="form.roleIds" multiple placeholder="可多选" style="width: 100%">
            <el-option
              v-for="role in roles.filter((r) => r.status === 1)"
              :key="role.id"
              :label="role.roleName"
              :value="role.id"
              :disabled="role.roleCode === 'SUPER_ADMIN' && editing?.id !== 1"
            />
          </el-select>
          <div class="role-tip">超级管理员角色仅可授予初始账号（BR-SYS-03）</div>
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

    <!-- 初始密码展示 -->
    <el-dialog v-model="pwdDialog.visible" title="一次性初始密码" width="420px">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="请立即复制并安全告知该用户，密码仅展示一次"
      />
      <div class="pwd-box">{{ pwdDialog.password }}</div>
      <template #footer>
        <el-button type="primary" @click="copyPassword">复制密码</el-button>
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
    background: #f5f7fa;
    border-radius: 6px;
  }
}
</style>
