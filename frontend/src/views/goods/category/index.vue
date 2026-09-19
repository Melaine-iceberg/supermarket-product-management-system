<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, TreeNodeData } from 'element-plus'
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
const treeRef = ref()
const tree = ref<CategoryNode[]>([])
const keyword = ref('')

const defaultProps = { children: 'children', label: 'name' }

async function load() {
  loading.value = true
  try {
    tree.value = await getCategoryTree()
  } finally {
    loading.value = false
  }
}

// 关键字过滤并自动定位命中节点（FR-CAT-01）
watch(keyword, (val) => treeRef.value?.filter(val))

function filterNode(value: string, data: TreeNodeData) {
  return !value || String((data as CategoryNode).name).includes(value.trim())
}

/** 展开全部 / 折叠全部 */
function toggleExpand(expand: boolean) {
  const nodesMap: Record<string, { expanded: boolean }> = treeRef.value?.store?.nodesMap ?? {}
  Object.values(nodesMap).forEach((n) => (n.expanded = expand))
}

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
const formRef = ref<FormInstance>()
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
  if (!editing.value) return [{ id: 0, name: '（作为一级分类）', level: 0, path: '' }, ...flat.value]
  const exclude: Set<number> = new Set([editing.value.id])
  const collect = (n: CategoryNode) => {
    n.children.forEach((c) => {
      exclude.add(c.id)
      collect(c)
    })
  }
  collect(editing.value)
  return [
    { id: 0, name: '（作为一级分类）', level: 0, path: '' },
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
  await formRef.value?.validate()
  // 跨级移动二次确认（FR-CAT-06）：提示受影响的商品数量
  if (editing.value && editing.value.parentId !== form.parentId) {
    const moving = editing.value.goodsCount
    try {
      await ElMessageBox.confirm(
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
      ElMessage.success('分类已更新')
    } else {
      await createCategory({ ...form })
      ElMessage.success('分类已新增')
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
      await ElMessageBox.confirm(`${tips.join('，')}。`, '停用分类确认', {
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
    ElMessage.success(disabling ? '分类已停用' : '分类已启用')
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
    await ElMessageBox.confirm(
      `删除后不可恢复，确认删除分类【${node.name}】吗？`,
      '删除分类确认',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await deleteCategory(node.id)
    ElMessage.success('分类已删除')
    load()
  } catch {
    // 拦截器已提示（存在子分类/商品关联时后端拒绝，BR-CAT-03）
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <el-card>
      <div class="toolbar">
        <div class="toolbar-left">
          <el-input
            v-model="keyword"
            placeholder="按名称过滤，自动定位命中节点"
            clearable
            class="filter-input"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-button @click="toggleExpand(true)">展开全部</el-button>
          <el-button @click="toggleExpand(false)">折叠全部</el-button>
        </div>
        <el-button
          v-if="auth.hasPerm('sys:cat:create')"
          type="primary"
          @click="openCreate()"
        >
          <el-icon><Plus /></el-icon>新增一级分类
        </el-button>
      </div>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="tip"
        title="分类最多 3 级；商品只能挂在叶子分类上（BR-CAT-04）。停用级联其下子分类；有商品关联的分类只能停用不能删除。"
      />

      <el-tree
        ref="treeRef"
        v-loading="loading"
        :data="tree"
        :props="defaultProps"
        :filter-node-method="filterNode"
        node-key="id"
        default-expand-all
        :expand-on-click-node="false"
        highlight-current
      >
        <template #default="{ data }">
          <div class="node">
            <div class="node-main">
              <span class="node-name" :class="{ disabled: data.status === 0 }">
                {{ data.name }}
              </span>
              <el-tag size="small" effect="plain" class="level-tag">
                {{ ['一级', '二级', '三级'][data.level - 1] }}
              </el-tag>
              <el-tag
                size="small"
                :type="data.status === 1 ? 'success' : 'info'"
                effect="light"
              >
                {{ data.status === 1 ? '启用' : '已停用' }}
              </el-tag>
              <span class="goods-count" title="该分类及其子分类下的商品数量">
                <el-icon><Goods /></el-icon>{{ data.goodsCount }}
              </span>
            </div>
            <div class="node-actions" @click.stop>
              <el-button
                v-if="auth.hasPerm('sys:cat:create') && data.level < 3 && data.status === 1"
                link
                type="primary"
                size="small"
                @click="openCreate(data)"
              >新增子分类</el-button>
              <el-button
                v-if="auth.hasPerm('sys:cat:update')"
                link
                type="primary"
                size="small"
                @click="openEdit(data)"
              >编辑</el-button>
              <el-button
                v-if="auth.hasPerm('sys:cat:status')"
                link
                :type="data.status === 1 ? 'warning' : 'success'"
                size="small"
                @click="toggleStatus(data)"
              >{{ data.status === 1 ? '停用' : '启用' }}</el-button>
              <el-button
                v-if="auth.hasPerm('sys:cat:delete')"
                link
                type="danger"
                size="small"
                @click="remove(data)"
              >删除</el-button>
            </div>
          </div>
        </template>
      </el-tree>
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="editing ? `编辑分类：${editing.name}` : '新增分类'"
      width="460px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="上级分类">
          <el-tree-select
            v-model="form.parentId"
            :data="parentOptions"
            :props="{ label: 'path', value: 'id' }"
            check-strictly
            :render-after-expand="false"
            default-expand-all
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="分类名称" prop="name">
          <el-input v-model="form.name" maxlength="30" placeholder="同级下名称不可重复" />
        </el-form-item>
        <el-form-item label="排序号">
          <el-input-number v-model="form.sortNo" :min="0" :max="9999" />
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

  :deep(.el-tree-node__content) {
    height: 36px;
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
          color: #909399;
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
        color: #909399;
        font-size: 12px;
      }
    }

    .node-actions {
      visibility: hidden;
      display: flex;
    }

    &:hover .node-actions {
      visibility: visible;
    }
  }
}
</style>
