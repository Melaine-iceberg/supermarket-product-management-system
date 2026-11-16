<script setup lang="ts">
import { notify } from '@/utils/notify'
import { confirm } from '@/utils/confirm'
import { defineComponent, h, onMounted, reactive, ref, type Component, type PropType } from 'vue'
import UBadge from '@nuxt/ui/components/Badge.vue'
import UCheckbox from '@nuxt/ui/components/Checkbox.vue'
import { useAuthStore } from '@/stores/auth'
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
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await updateRole(editing.value.id, { ...form })
      notify.success('角色已更新')
    } else {
      await createRole({ ...form })
      notify.success('角色已创建')
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
  await confirm(
    disabling
      ? `停用角色「${row.roleName}」后，拥有该角色的用户将失去对应权限，确定停用？`
      : `确定启用角色「${row.roleName}」？`,
    disabling ? '停用角色' : '启用角色',
    { type: 'warning' },
  )
  await updateRoleStatus(row.id, disabling ? 0 : 1)
  notify.success(disabling ? '已停用' : '已启用')
  load()
}

// ---------- 权限分配抽屉 ----------
const drawerVisible = ref(false)
const drawerRole = ref<SysRoleVO | null>(null)
const treeData = ref<MenuNode[]>([])
const treeLoading = ref(false)
const permSaving = ref(false)

/** 已勾选的叶子节点（父节点状态由子节点推导，保持父子联动） */
const checkedLeafIds = ref<Set<number>>(new Set())

async function openPermDrawer(row: SysRoleVO) {
  drawerRole.value = row
  drawerVisible.value = true
  treeLoading.value = true
  try {
    if (!treeData.value.length) {
      treeData.value = await getPermissionTree()
    }
    const checked = await getRolePerms(row.id)
    // 仅勾选叶子节点（按钮），父节点按父子联动展示
    const leafIds = collectLeafIds(treeData.value, new Set(checked))
    checkedLeafIds.value = new Set(leafIds)
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

function collectLeaves(node: MenuNode): number[] {
  if (!node.children.length) return [node.id]
  return node.children.flatMap((child) => collectLeaves(child))
}

function nodeState(node: MenuNode): 'checked' | 'half' | 'unchecked' {
  if (!node.children.length) {
    return checkedLeafIds.value.has(node.id) ? 'checked' : 'unchecked'
  }
  const states = node.children.map((child) => nodeState(child))
  if (states.every((state) => state === 'checked')) return 'checked'
  if (states.every((state) => state === 'unchecked')) return 'unchecked'
  return 'half'
}

/** 勾选/取消整棵子树，父节点状态由子节点自动推导 */
function toggleNode(node: MenuNode) {
  const check = nodeState(node) !== 'checked'
  const next = new Set(checkedLeafIds.value)
  for (const id of collectLeaves(node)) {
    if (check) next.add(id)
    else next.delete(id)
  }
  checkedLeafIds.value = next
}

function walkTree(nodes: MenuNode[], visit: (node: MenuNode) => void) {
  for (const node of nodes) {
    visit(node)
    if (node.children.length) walkTree(node.children, visit)
  }
}

/** 本地实现：返回全选（含父节点）的权限 ID */
function getCheckedKeys(): number[] {
  const result: number[] = []
  walkTree(treeData.value, (node) => {
    if (nodeState(node) === 'checked') result.push(node.id)
  })
  return result
}

/** 本地实现：返回半选（父节点部分勾选）的权限 ID */
function getHalfCheckedKeys(): number[] {
  const result: number[] = []
  walkTree(treeData.value, (node) => {
    if (nodeState(node) === 'half') result.push(node.id)
  })
  return result
}

const PermTreeNode: Component = defineComponent({
  name: 'PermTreeNode',
  props: {
    nodes: { type: Array as PropType<MenuNode[]>, required: true },
    depth: { type: Number, default: 0 },
  },
  setup(props) {
    const typeMeta = (type: MenuNode['permType']) => {
      if (type === 'D') return { color: 'warning' as const, label: '目录' }
      if (type === 'M') return { color: 'primary' as const, label: '菜单' }
      return { color: 'neutral' as const, label: '按钮' }
    }

    return () =>
      h(
        'div',
        { class: 'flex flex-col' },
        props.nodes.map((node) => {
          const state = nodeState(node)
          const meta = typeMeta(node.permType)
          return h('div', { key: node.id }, [
            h(
              'div',
              {
                class: 'flex items-center gap-1.5 rounded-md py-1 pr-2 hover:bg-elevated',
                style: { paddingLeft: `${props.depth * 18 + 6}px` },
              },
              [
                h(UCheckbox, {
                  'modelValue':
                    state === 'checked' ? true : state === 'half' ? 'indeterminate' : false,
                  'onUpdate:modelValue': () => toggleNode(node),
                  'aria-label': node.name,
                }),
                h(UBadge, { color: meta.color, variant: 'outline', size: 'sm' }, () => meta.label),
                h('span', { class: 'text-sm text-default' }, node.name),
                node.permCode
                  ? h('span', { class: 'font-mono text-xs text-muted' }, node.permCode)
                  : null,
              ],
            ),
            node.children.length
              ? h(PermTreeNode, { nodes: node.children, depth: props.depth + 1 })
              : null,
          ])
        }),
      )
  },
})

async function savePerms() {
  if (!drawerRole.value) return
  const permIds = [...getCheckedKeys(), ...getHalfCheckedKeys()]
  permSaving.value = true
  try {
    await assignRolePerms(drawerRole.value.id, permIds)
    notify.success('权限已保存，对在线用户即时生效')
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
    <UCard>
      <template #header>
        <div class="card-head">
          <span>角色列表（内置角色不可删除，权限可调整）</span>
          <UButton
            v-if="auth.hasPerm('sys:role:create')"
            icon="i-lucide-plus"
            label="新增角色"
            @click="openCreate"
          />
        </div>
      </template>

      <AppTable :data="list" :loading="loading" border stripe>
        <AppTableColumn prop="roleName" label="角色名称" width="140" />
        <AppTableColumn label="角色代码" width="150">
          <template #default="{ row }">
            <UBadge variant="outline">{{ row.roleCode }}</UBadge>
          </template>
        </AppTableColumn>
        <AppTableColumn prop="description" label="描述" min-width="260">
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </AppTableColumn>
        <AppTableColumn label="类型" width="90">
          <template #default="{ row }">
            <UBadge :color="row.builtin ? 'warning' : 'neutral'" size="sm" variant="soft">
              {{ row.builtin ? '内置' : '自定义' }}
            </UBadge>
          </template>
        </AppTableColumn>
        <AppTableColumn label="状态" width="80">
          <template #default="{ row }">
            <UBadge :color="row.status === 1 ? 'success' : 'error'" variant="soft">
              {{ row.status === 1 ? '启用' : '停用' }}
            </UBadge>
          </template>
        </AppTableColumn>
        <AppTableColumn label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <UButton
              v-if="auth.hasPerm('sys:role:update')"
              variant="link"
              size="sm"
              label="编辑"
              @click="openEdit(row as SysRoleVO)"
            />
            <UButton
              v-if="auth.hasPerm('sys:role:update')"
              variant="link"
              size="sm"
              label="分配权限"
              :disabled="row.roleCode === 'SUPER_ADMIN'"
              @click="openPermDrawer(row as SysRoleVO)"
            />
            <UButton
              v-if="auth.hasPerm('sys:role:status')"
              variant="link"
              size="sm"
              :color="row.status === 1 ? 'error' : 'success'"
              :label="row.status === 1 ? '停用' : '启用'"
              :disabled="row.roleCode === 'SUPER_ADMIN'"
              @click="toggleStatus(row as SysRoleVO)"
            />
          </template>
        </AppTableColumn>
      </AppTable>
    </UCard>

    <!-- 新增 / 编辑 -->
    <AppModal
      v-model="dialogVisible"
      :title="editing ? `编辑角色：${editing.roleName}` : '新增角色'"
      width="460px"
    >
      <AppForm ref="formRef" :model="form" :rules="rules" label-width="80px">
        <AppFormItem label="角色名称" prop="roleName">
          <AppInput v-model="form.roleName" maxlength="30" />
        </AppFormItem>
        <AppFormItem label="角色代码" prop="roleCode">
          <AppInput
            v-model="form.roleCode"
            maxlength="30"
            :disabled="!!editing?.builtin"
            placeholder="如 PURCHASER"
          />
        </AppFormItem>
        <AppFormItem label="描述">
          <AppInput v-model="form.description" type="textarea" maxlength="200" />
        </AppFormItem>
      </AppForm>
      <template #footer>
        <UButton color="neutral" variant="outline" label="取消" @click="dialogVisible = false" />
        <UButton :loading="saving" label="保存" @click="save" />
      </template>
    </AppModal>

    <!-- 权限分配抽屉 -->
    <AppDrawer
      v-model="drawerVisible"
      :title="`分配权限：${drawerRole?.roleName || ''}`"
      size="420px"
    >
      <UAlert
        v-if="drawerRole"
        color="info"
        icon="i-lucide-info"
        title="目录 / 菜单 / 按钮三级权限，保存后对在线用户 1 分钟内生效"
        class="drawer-alert"
      />
      <div v-if="treeLoading" class="py-8 text-center text-sm text-muted">加载中…</div>
      <PermTreeNode v-else :nodes="treeData" />
      <template #footer>
        <UButton color="neutral" variant="outline" label="取消" @click="drawerVisible = false" />
        <UButton :loading="permSaving" label="保存权限" @click="savePerms" />
      </template>
    </AppDrawer>
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
}
</style>
