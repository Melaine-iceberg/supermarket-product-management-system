<template>
  <div class="error-page">
    <div class="max-w-md text-center">
      <UIcon name="i-lucide-search-x" class="mx-auto size-14 text-muted" />
      <h1 class="mt-4 text-xl font-semibold text-default">404 页面不存在</h1>
      <p class="mt-2 text-sm text-muted">您访问的页面不存在或已被移除</p>
      <UButton class="mt-6" label="返回首页" @click="$router.push('/home')" />
    </div>
  </div>
</template>

<style scoped>
.error-page {
  height: 100vh;
  height: 100dvh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
  background: var(--ui-bg-muted);
}
</style>
