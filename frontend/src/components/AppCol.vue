<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    span?: number
    offset?: number
    xs?: number
    sm?: number
    md?: number
    lg?: number
    xl?: number
  }>(),
  {
    span: 24,
    offset: 0,
    xs: undefined,
    sm: undefined,
    md: undefined,
    lg: undefined,
    xl: undefined,
  },
)

const percent = (value: number) => `${(value / 24) * 100}%`

const colStyle = computed(() => ({
  '--app-col-span': percent(props.span),
  '--app-col-xs': props.xs != null ? percent(props.xs) : undefined,
  '--app-col-sm': props.sm != null ? percent(props.sm) : undefined,
  '--app-col-md': props.md != null ? percent(props.md) : undefined,
  '--app-col-lg': props.lg != null ? percent(props.lg) : undefined,
  '--app-col-xl': props.xl != null ? percent(props.xl) : undefined,
  'marginLeft': props.offset ? percent(props.offset) : undefined,
}))
</script>

<template>
  <div class="app-col" :style="colStyle">
    <slot />
  </div>
</template>

<style scoped>
.app-col {
  width: var(--app-col-span, 100%);
  padding-left: var(--app-col-gutter, 0);
  padding-right: var(--app-col-gutter, 0);
}

@media (max-width: 640px) {
  .app-col {
    width: var(--app-col-xs, var(--app-col-span, 100%));
  }
}

@media (min-width: 641px) and (max-width: 768px) {
  .app-col {
    width: var(--app-col-sm, var(--app-col-span, 100%));
  }
}

@media (min-width: 769px) and (max-width: 1024px) {
  .app-col {
    width: var(--app-col-md, var(--app-col-sm, var(--app-col-span, 100%)));
  }
}

@media (min-width: 1025px) and (max-width: 1280px) {
  .app-col {
    width: var(
      --app-col-lg,
      var(--app-col-md, var(--app-col-sm, var(--app-col-span, 100%)))
    );
  }
}

@media (min-width: 1281px) {
  .app-col {
    width: var(
      --app-col-xl,
      var(--app-col-lg, var(--app-col-md, var(--app-col-sm, var(--app-col-span, 100%))))
    );
  }
}
</style>
