<script setup lang="ts">
import { nextTick, onMounted, reactive, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import type { ElTree } from 'element-plus'
import {
  listRoles,
  createRole,
  updateRole,
  updateRoleStatus,
  getRolePerms,
  assignRolePerms,
  getPermissionTree,
  type SysRoleVO,
} from '@/api/system'
import type { MenuNode } from '@/api/auth'

const auth = useAuthStore()

// ---------- 列表 ----------
const loading = ref(false)
const list = ref<SysRoleVO[]>([])

async function load() {
  loading.value = true
  try {
    list.value = await listRoles()
  } finally {
    loading.value = false
  }
}

// ---------- 新增 / 编辑 ----------
const dialogVisible = ref(false)
const editing = ref<SysRoleVO | null>(null)
const formRef = ref()
const saving = ref(false)
const form = reactive({ roleName: '', roleCode: '', description: '' })

const rules = {
  roleName: [{ required: true, message: '请输入角色名称', trigger: 'blur' }],
  roleCode: [
    { required: true, message: '请输入角色代码', trigger: 'blur' },
    { pattern: /^[A-Z][A-Z0-9_]{1,29}$/, message: '大写字母开头，仅含大写字母/数字/下划线', trigger: 'blur' },
  ],
}

function openCreate() {
  editing.value = null
  Object.assign(form, { roleName: '', roleCode: '', description: '' })
  dialogVisible.value = true
}

function openEdit(row: SysRoleVO) {
  editing.value = row
  Object.assign(form, {
    roleName: row.roleName,
    roleCode: row.roleCode,
    description: row.description || '',
  })
  dialogVisible.value = true
}

async function save() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (editing.value) {
      await updateRole(editing.value.id, { ...form })
      ElMessage.success('角色已更新')
    } else {
      await createRole({ ...form })
      ElMessage.success('角色已创建')
    }
    dialogVisible.value = false
    load()
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

// ---------- 停用 / 启用 ----------
async function toggleStatus(row: SysRoleVO) {
  const disabling = row.status === 1
  await ElMessageBox.confirm(
    disabling
      ? `停用角色「${row.roleName}」后，拥有该角色的用户将失去对应权限，确定停用？`
      : `确定启用角色「${row.roleName}」？`,
    disabling ? '停用角色' : '启用角色',
    { type: 'warning' },
  )
  await updateRoleStatus(row.id, disabling ? 0 : 1)
  ElMessage.success(disabling ? '已停用' : '已启用')
  load()
}

// ---------- 权限分配抽屉 ----------
const treeRef = ref<InstanceType<typeof ElTree>>()
const drawerVisible = ref(false)
const drawerRole = ref<SysRoleVO | null>(null)
const treeData = ref<MenuNode[]>([])
const treeLoading = ref(false)
const permSaving = ref(false)

async function openPermDrawer(row: SysRoleVO) {
  drawerRole.value = row
  drawerVisible.value = true
  treeLoading.value = true
  try {
    if (!treeData.value.length) {
      treeData.value = await getPermissionTree()
    }
    const checked = await getRolePerms(row.id)
    await nextTick()
    // 仅勾选叶子节点（按钮），父节点按父子联动展示
    const leafIds = collectLeafIds(treeData.value, new Set(checked))
    treeRef.value?.setCheckedKeys(leafIds)
  } finally {
    treeLoading.value = false
  }
}

function collectLeafIds(nodes: MenuNode[], checked: Set<number>): number[] {
  const result: number[] = []
  for (const node of nodes) {
    if (node.children?.length) {
      result.push(...collectLeafIds(node.children, checked))
    } else if (checked.has(node.id)) {
      result.push(node.id)
    }
  }
  return result
}

async function savePerms() {
  if (!drawerRole.value) return
  const tree = treeRef.value
  if (!tree) return
  const permIds = [...(tree.getCheckedKeys() as number[]), ...(tree.getHalfCheckedKeys() as number[])]
  permSaving.value = true
  try {
    await assignRolePerms(drawerRole.value.id, permIds)
    ElMessage.success('权限已保存，对在线用户即时生效')
    drawerVisible.value = false
    // 权限变更后刷新自身菜单（NFR-SEC-02）
    auth.refreshMe().catch(() => undefined)
  } catch {
    // 拦截器已提示
  } finally {
    permSaving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <el-card>
      <template #header>
        <div class="card-head">
          <span>角色列表（内置角色不可删除，权限可调整）</span>
          <el-button v-if="auth.hasPerm('sys:role:create')" type="primary" @click="openCreate">
            <el-icon><Plus /></el-icon>新增角色
          </el-button>
        </div>
      </template>

      <el-table v-loading="loading" :data="list" stripe>
        <el-table-column prop="roleName" label="角色名称" width="140" />
        <el-table-column label="角色代码" width="150">
          <template #default="{ row }">
            <el-tag effect="plain">{{ row.roleCode }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="260">
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag :type="row.builtin ? 'warning' : 'info'" size="small">
              {{ row.builtin ? '内置' : '自定义' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="auth.hasPerm('sys:role:update')"
              link
              type="primary"
              @click="openEdit(row as SysRoleVO)"
            >编辑</el-button>
            <el-button
              v-if="auth.hasPerm('sys:role:update')"
              link
              type="primary"
              :disabled="row.roleCode === 'SUPER_ADMIN'"
              @click="openPermDrawer(row as SysRoleVO)"
            >分配权限</el-button>
            <el-button
              v-if="auth.hasPerm('sys:role:status')"
              link
              :type="row.status === 1 ? 'danger' : 'success'"
              :disabled="row.roleCode === 'SUPER_ADMIN'"
              @click="toggleStatus(row as SysRoleVO)"
            >{{ row.status === 1 ? '停用' : '启用' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新增 / 编辑 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editing ? `编辑角色：${editing.roleName}` : '新增角色'"
      width="460px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="角色名称" prop="roleName">
          <el-input v-model="form.roleName" maxlength="30" />
        </el-form-item>
        <el-form-item label="角色代码" prop="roleCode">
          <el-input
            v-model="form.roleCode"
            maxlength="30"
            :disabled="!!editing?.builtin"
            placeholder="如 PURCHASER"
          />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" maxlength="200" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 权限分配抽屉 -->
    <el-drawer
      v-model="drawerVisible"
      :title="`分配权限：${drawerRole?.roleName || ''}`"
      size="420px"
    >
      <el-alert
        v-if="drawerRole"
        type="info"
        :closable="false"
        show-icon
        :title="`目录 / 菜单 / 按钮三级权限，保存后对在线用户 1 分钟内生效`"
        class="drawer-alert"
      />
      <el-tree
        ref="treeRef"
        v-loading="treeLoading"
        :data="treeData"
        :props="{ label: 'name', children: 'children' }"
        node-key="id"
        show-checkbox
        default-expand-all
      >
        <template #default="{ data }">
          <span class="tree-node">
            <el-tag
              :type="data.permType === 'D' ? 'warning' : data.permType === 'M' ? 'primary' : 'info'"
              size="small"
              effect="plain"
            >
              {{ data.permType === 'D' ? '目录' : data.permType === 'M' ? '菜单' : '按钮' }}
            </el-tag>
            <span>{{ data.name }}</span>
            <span v-if="data.permCode" class="perm-code">{{ data.permCode }}</span>
          </span>
        </template>
      </el-tree>
      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="permSaving" @click="savePerms">保存权限</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
.page {
  .card-head {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .drawer-alert {
    margin-bottom: 12px;
  }

  .tree-node {
    display: flex;
    align-items: center;
    gap: 6px;

    .perm-code {
      color: #c0c4cc;
      font-size: 12px;
      font-family: monospace;
    }
  }
}
</style>
