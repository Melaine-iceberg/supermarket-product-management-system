<script setup lang="ts">
import { notify } from '@/utils/notify'
import { confirm } from '@/utils/confirm'
import { computed, onMounted, reactive, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import {
  getCategoryTree,
  createCategory,
  updateCategory,
  updateCategoryStatus,
  deleteCategory,
  type CategoryNode,
} from '@/api/goods'

const auth = useAuthStore()

const loading = ref(false)
const tree = ref<CategoryNode[]>([])
const keyword = ref('')
const expandedIds = ref<Set<number>>(new Set())
const currentId = ref<number | null>(null)

/** 收集全部节点 id（默认展开全部 / 展开全部） */
function collectIds(nodes: CategoryNode[], out: Set<number> = new Set()): Set<number> {
  for (const node of nodes) {
    out.add(node.id)
    collectIds(node.children, out)
  }
  return out
}

async function load() {
  loading.value = true
  try {
    tree.value = await getCategoryTree()
    // 原 el-tree 的 default-expand-all：加载后默认展开全部节点
    expandedIds.value = collectIds(tree.value)
  } finally {
    loading.value = false
  }
}

/** 展开全部 / 折叠全部 */
function toggleExpand(expand: boolean) {
  expandedIds.value = expand ? collectIds(tree.value) : new Set()
}

function isExpanded(id: number) {
  return expandedIds.value.has(id)
}

function toggleNode(node: CategoryNode) {
  const next = new Set(expandedIds.value)
  if (next.has(node.id)) next.delete(node.id)
  else next.add(node.id)
  expandedIds.value = next
}

// 关键字过滤（FR-CAT-01）：命中节点保留祖先链，并自动展开命中路径
function matchNode(node: CategoryNode, value: string) {
  return !value || node.name.includes(value)
}

function subtreeMatches(node: CategoryNode, value: string): boolean {
  if (matchNode(node, value)) return true
  return node.children.some((child) => subtreeMatches(child, value))
}

/** 扁平化可见节点（脚本内实现关键字过滤 / 展开折叠） */
interface VisibleRow {
  node: CategoryNode
  depth: number
}

const visibleRows = computed<VisibleRow[]>(() => {
  const value = keyword.value.trim()
  const filtering = !!value
  const rows: VisibleRow[] = []
  const walk = (nodes: CategoryNode[], depth: number) => {
    for (const node of nodes) {
      if (filtering && !subtreeMatches(node, value)) continue
      rows.push({ node, depth })
      const open = filtering ? node.children.length > 0 : expandedIds.value.has(node.id)
      if (open && node.children.length) walk(node.children, depth + 1)
    }
  }
  walk(tree.value, 0)
  return rows
})

// 扁平化（编辑弹窗里做上级候选与排除子孙）
interface FlatNode {
  id: number
  name: string
  level: number
  path: string
}
const flat = computed<FlatNode[]>(() => {
  const out: FlatNode[] = []
  const walk = (nodes: CategoryNode[], prefix: string) => {
    nodes
      .slice()
      .sort((a, b) => a.sortNo - b.sortNo)
      .forEach((n) => {
        const path = prefix ? `${prefix} / ${n.name}` : n.name
        out.push({ id: n.id, name: n.name, level: n.level, path })
        walk(n.children, path)
      })
  }
  walk(tree.value, '')
  return out
})

// ---------- 新增 / 编辑弹窗 ----------
const dialogVisible = ref(false)
const editing = ref<CategoryNode | null>(null)
const formRef = ref()
const saving = ref(false)
const form = reactive({ name: '', parentId: 0, sortNo: 1, status: 1 })

const rules = {
  name: [
    { required: true, message: '请输入分类名称', trigger: 'blur' },
    { min: 1, max: 30, message: '名称长度 1~30 个字符', trigger: 'blur' },
    {
      validator: (_r: unknown, v: string, cb: (e?: Error) => void) =>
        v !== v.trim() ? cb(new Error('名称不能包含前后空格')) : cb(),
      trigger: 'blur',
    },
  ],
}

/** 上级分类候选：编辑时排除自身与子孙（BR-CAT-02 环校验） */
const parentOptions = computed<FlatNode[]>(() => {
  if (!editing.value)
    return [
      { id: 0, name: '（作为一级分类）', level: 0, path: '（作为一级分类）' },
      ...flat.value,
    ]
  const exclude: Set<number> = new Set([editing.value.id])
  const collect = (n: CategoryNode) => {
    n.children.forEach((c) => {
      exclude.add(c.id)
      collect(c)
    })
  }
  collect(editing.value)
  return [
    { id: 0, name: '（作为一级分类）', level: 0, path: '（作为一级分类）' },
    ...flat.value.filter((f) => !exclude.has(f.id)),
  ]
})

function openCreate(parent?: CategoryNode) {
  editing.value = null
  Object.assign(form, {
    name: '',
    parentId: parent?.id ?? 0,
    sortNo: 1,
    status: 1,
  })
  dialogVisible.value = true
}

function openEdit(node: CategoryNode) {
  editing.value = node
  Object.assign(form, {
    name: node.name,
    parentId: node.parentId,
    sortNo: node.sortNo,
    status: node.status,
  })
  dialogVisible.value = true
}

async function save() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  // 跨级移动二次确认（FR-CAT-06）：提示受影响的商品数量
  if (editing.value && editing.value.parentId !== form.parentId) {
    const moving = editing.value.goodsCount
    try {
      await confirm(
        `将分类【${editing.value.name}】移动到新上级，其下 ${moving} 个商品的分类归属将随之变化，` +
          '品类统计口径会同步调整。确认移动吗？',
        '移动分类确认',
        { type: 'warning', confirmButtonText: '确认移动', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }
  saving.value = true
  try {
    if (editing.value) {
      await updateCategory(editing.value.id, { ...form })
      notify.success('分类已更新')
    } else {
      await createCategory({ ...form })
      notify.success('分类已新增')
    }
    dialogVisible.value = false
    load()
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

// ---------- 停用 / 启用（FR-CAT-04） ----------
async function toggleStatus(node: CategoryNode) {
  const disabling = node.status === 1
  if (disabling) {
    // IR-UI-03：危险操作明确后果（级联停用 + 商品不可选）
    const childCount = countNodes(node) - 1
    const tips = [`确认后分类【${node.name}】将被停用`]
    if (childCount > 0) tips.push(`其下 ${childCount} 个子分类将一并停用`)
    if (node.goodsCount > 0) tips.push(`其下 ${node.goodsCount} 个商品将无法在新单据中选择`)
    try {
      await confirm(`${tips.join('，')}。`, '停用分类确认', {
        type: 'warning',
        confirmButtonText: '确认停用',
        cancelButtonText: '取消',
      })
    } catch {
      return
    }
  }
  try {
    await updateCategoryStatus(node.id, disabling ? 0 : 1)
    notify.success(disabling ? '分类已停用' : '分类已启用')
    load()
  } catch {
    // 拦截器已提示（如上级停用时不可启用）
  }
}

function countNodes(node: CategoryNode): number {
  return 1 + node.children.reduce((sum, c) => sum + countNodes(c), 0)
}

// ---------- 删除（FR-CAT-05） ----------
async function remove(node: CategoryNode) {
  try {
    await confirm(
      `删除后不可恢复，确认删除分类【${node.name}】吗？`,
      '删除分类确认',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await deleteCategory(node.id)
    notify.success('分类已删除')
    load()
  } catch {
    // 拦截器已提示（存在子分类/商品关联时后端拒绝，BR-CAT-03）
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <UCard>
      <div class="toolbar">
        <div class="toolbar-left">
          <AppInput
            v-model="keyword"
            placeholder="按名称过滤，自动定位命中节点"
            clearable
            prefix-icon="i-lucide-search"
            class="filter-input"
          />
          <UButton
            color="neutral"
            variant="outline"
            label="展开全部"
            @click="toggleExpand(true)"
          />
          <UButton
            color="neutral"
            variant="outline"
            label="折叠全部"
            @click="toggleExpand(false)"
          />
        </div>
        <UButton
          v-if="auth.hasPerm('sys:cat:create')"
          icon="i-lucide-plus"
          label="新增一级分类"
          @click="openCreate()"
        />
      </div>

      <UAlert
        color="info"
        icon="i-lucide-info"
        class="tip"
        title="分类最多 3 级；商品只能挂在叶子分类上（BR-CAT-04）。停用级联其下子分类；有商品关联的分类只能停用不能删除。"
      />

      <div v-if="loading" class="tree-state">加载中…</div>
      <div v-else-if="!visibleRows.length" class="tree-state">暂无分类</div>
      <div v-else class="category-tree">
        <div
          v-for="{ node, depth } in visibleRows"
          :key="node.id"
          class="tree-row"
          :class="{ 'is-current': currentId === node.id }"
          @click="currentId = node.id"
        >
          <span class="tree-indent" :style="{ width: `${depth * 20}px` }" />
          <span class="tree-toggle">
            <UButton
              v-if="node.children.length"
              color="neutral"
              variant="ghost"
              size="xs"
              square
              icon="i-lucide-chevron-right"
              :ui="{
                leadingIcon: [
                  'transition-transform duration-200',
                  isExpanded(node.id) ? 'rotate-90' : '',
                ],
              }"
              :aria-label="isExpanded(node.id) ? '折叠分类' : '展开分类'"
              @click.stop="toggleNode(node)"
            />
          </span>
          <div class="node">
            <div class="node-main">
              <span class="node-name" :class="{ disabled: node.status === 0 }">
                {{ node.name }}
              </span>
              <UBadge color="neutral" variant="outline" size="sm" class="level-tag">
                {{ ['一级', '二级', '三级'][node.level - 1] }}
              </UBadge>
              <UBadge
                :color="node.status === 1 ? 'success' : 'neutral'"
                variant="soft"
                size="sm"
              >
                {{ node.status === 1 ? '启用' : '已停用' }}
              </UBadge>
              <span class="goods-count" title="该分类及其子分类下的商品数量">
                <UIcon name="i-lucide-package" />{{ node.goodsCount }}
              </span>
            </div>
            <div class="node-actions" @click.stop>
              <UButton
                v-if="auth.hasPerm('sys:cat:create') && node.level < 3 && node.status === 1"
                variant="link"
                size="sm"
                label="新增子分类"
                @click="openCreate(node)"
              />
              <UButton
                v-if="auth.hasPerm('sys:cat:update')"
                variant="link"
                size="sm"
                label="编辑"
                @click="openEdit(node)"
              />
              <UButton
                v-if="auth.hasPerm('sys:cat:status')"
                variant="link"
                size="sm"
                :color="node.status === 1 ? 'warning' : 'success'"
                :label="node.status === 1 ? '停用' : '启用'"
                @click="toggleStatus(node)"
              />
              <UButton
                v-if="auth.hasPerm('sys:cat:delete')"
                variant="link"
                size="sm"
                color="error"
                label="删除"
                @click="remove(node)"
              />
            </div>
          </div>
        </div>
      </div>
    </UCard>

    <AppModal
      v-model="dialogVisible"
      :title="editing ? `编辑分类：${editing.name}` : '新增分类'"
      width="460px"
    >
      <AppForm ref="formRef" :model="form" :rules="rules" label-width="80px">
        <AppFormItem label="上级分类">
          <AppTreeSelect
            v-model="form.parentId"
            :data="parentOptions"
            :props="{ label: 'path' }"
            node-key="id"
            :clearable="false"
            style="width: 100%"
          />
        </AppFormItem>
        <AppFormItem label="分类名称" prop="name">
          <AppInput v-model="form.name" maxlength="30" placeholder="同级下名称不可重复" />
        </AppFormItem>
        <AppFormItem label="排序号">
          <AppInputNumber v-model="form.sortNo" :min="0" :max="9999" />
        </AppFormItem>
        <AppFormItem label="状态" label-as="span">
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

<script lang="ts">
export default { name: 'GoodsCategoryView' }
</script>

<style scoped lang="scss">
.page {
  .toolbar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 12px;

    .toolbar-left {
      display: flex;
      gap: 8px;

      .filter-input {
        width: 260px;
      }
    }
  }

  .tip {
    margin-bottom: 12px;
  }

  .tree-state {
    padding: 40px 0;
    text-align: center;
    font-size: 14px;
    color: var(--ui-text-muted);
  }

  .tree-row {
    display: flex;
    align-items: center;
    height: 36px;
    border-radius: 4px;
    cursor: pointer;

    &:hover,
    &.is-current {
      background-color: var(--ui-bg-elevated);
    }

    &:hover .node-actions {
      visibility: visible;
    }
  }

  .tree-indent {
    flex-shrink: 0;
  }

  .tree-toggle {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 22px;
    flex-shrink: 0;
  }

  .node {
    flex: 1;
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding-right: 8px;

    .node-main {
      display: flex;
      align-items: center;
      gap: 8px;

      .node-name {
        font-size: 14px;

        &.disabled {
          color: var(--ui-text-muted);
          text-decoration: line-through;
        }
      }

      .level-tag {
        font-size: 12px;
      }

      .goods-count {
        display: inline-flex;
        align-items: center;
        gap: 2px;
        color: var(--ui-text-muted);
        font-size: 12px;
      }
    }

    .node-actions {
      visibility: hidden;
      display: flex;
    }
  }
}
</style>
