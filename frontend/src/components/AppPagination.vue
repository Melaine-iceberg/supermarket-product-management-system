<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    total?: number
    currentPage?: number
    pageSize?: number
    pageSizes?: number[]
    layout?: string
    hideOnSinglePage?: boolean
    disabled?: boolean
    small?: boolean
  }>(),
  {
    total: 0,
    currentPage: 1,
    pageSize: 20,
    pageSizes: () => [10, 20, 50, 100],
    layout: 'total, sizes, prev, pager, next, jumper',
    hideOnSinglePage: false,
    disabled: false,
    small: false,
  },
)

const emit = defineEmits<{
  'update:currentPage': [value: number]
  'update:pageSize': [value: number]
  'current-change': [value: number]
  'size-change': [value: number]
  change: [payload: { page: number; size: number }]
}>()

const page = computed({
  get: () => props.currentPage || 1,
  set: (value: number) => {
    if (value === props.currentPage) return
    emit('update:currentPage', value)
    emit('current-change', value)
    emit('change', { page: value, size: props.pageSize })
  },
})

const size = computed({
  get: () => props.pageSize,
  set: (value: number) => {
    if (value === props.pageSize) return
    emit('update:pageSize', value)
    emit('size-change', value)
    // 页大小变化后回到第一页，避免落在不存在的页码
    if (props.currentPage !== 1) {
      emit('update:currentPage', 1)
      emit('current-change', 1)
    }
    emit('change', { page: 1, size: value })
  },
})

const has = (key: string) => props.layout.includes(key)
const showTotal = computed(() => has('total'))
const showSizes = computed(() => has('sizes'))
const showJumper = computed(() => has('jumper'))
const visible = computed(() => !(props.hideOnSinglePage && props.total <= props.pageSize))

const sizeItems = computed(() =>
  props.pageSizes.map((value) => ({ label: `${value} 条/页`, value })),
)

const jump = ref('')
watch(
  () => props.currentPage,
  () => {
    jump.value = ''
  },
)

function gotoPage() {
  const value = Number(jump.value)
  if (Number.isFinite(value) && value >= 1) page.value = Math.floor(value)
  jump.value = ''
}
</script>

<template>
  <div
    v-if="visible"
    class="app-pagination flex flex-wrap items-center justify-end gap-2 text-sm"
  >
    <span v-if="showTotal" class="text-muted">共 {{ total }} 条</span>

    <USelect
      v-if="showSizes"
      v-model="size"
      :items="sizeItems"
      size="sm"
      class="w-28"
      :disabled="disabled"
      aria-label="每页条数"
    />

    <UPagination
      v-model:page="page"
      :total="total"
      :items-per-page="pageSize"
      :disabled="disabled"
      :size="small ? 'xs' : 'sm'"
      color="neutral"
      variant="outline"
      active-color="primary"
      active-variant="solid"
    />

    <span v-if="showJumper" class="flex items-center gap-1.5 text-muted">
      前往
      <UInput
        v-model="jump"
        size="sm"
        inputmode="numeric"
        class="w-16"
        :disabled="disabled"
        aria-label="前往页码"
        @keyup.enter="gotoPage"
      />
      页
    </span>
  </div>
</template>
