<template>
  <div class="error-page">
    <el-result icon="warning" title="403 无权访问" sub-title="您没有访问该页面的权限，请联系管理员分配相应角色">
      <template #extra>
        <el-button type="primary" @click="$router.push('/home')">返回首页</el-button>
      </template>
    </el-result>
  </div>
</template>

<style scoped>
.error-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f7fa;
}
</style>
