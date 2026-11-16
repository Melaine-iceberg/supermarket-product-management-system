<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    gutter?: number
    justify?: 'start' | 'end' | 'center' | 'space-around' | 'space-between'
    align?: 'top' | 'middle' | 'bottom'
  }>(),
  {
    gutter: 0,
    justify: 'start',
    align: 'top',
  },
)

const rowStyle = computed(() => ({
  '--app-col-gutter': `${props.gutter / 2}px`,
}))

const justifyClass = computed(
  () =>
    ({
      start: 'justify-start',
      end: 'justify-end',
      center: 'justify-center',
      'space-around': 'justify-around',
      'space-between': 'justify-between',
    })[props.justify],
)

const alignClass = computed(
  () =>
    ({
      top: 'items-start',
      middle: 'items-center',
      bottom: 'items-end',
    })[props.align],
)
</script>

<template>
  <div class="app-row flex flex-wrap" :class="[justifyClass, alignClass]" :style="rowStyle">
    <slot />
  </div>
</template>
