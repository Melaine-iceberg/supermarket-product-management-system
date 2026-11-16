<template>
  <div class="error-page">
    <div class="max-w-md text-center">
      <UIcon name="i-lucide-shield-x" class="mx-auto size-14 text-warning" />
      <h1 class="mt-4 text-xl font-semibold text-default">403 无权访问</h1>
      <p class="mt-2 text-sm text-muted">
        您没有访问该页面的权限，请联系管理员分配相应角色
      </p>
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
