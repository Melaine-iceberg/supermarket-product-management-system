<script setup lang="ts">
import { computed } from 'vue'
import { iconName } from '@/utils/icons'

const props = defineProps<{
  name?: string
  size?: number | string
}>()

const resolved = computed(() => iconName(props.name))
const iconStyle = computed(() => {
  if (!props.size) return undefined
  return { fontSize: typeof props.size === 'number' ? `${props.size}px` : props.size }
})
</script>

<template>
  <UIcon v-if="resolved" :name="resolved" :style="iconStyle" />
</template>
