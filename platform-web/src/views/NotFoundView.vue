<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <div class="not-found">
    <p class="not-found__code">404</p>
    <p class="not-found__text">页面不存在或已被移除</p>
    <el-button type="primary" @click="router.push('/dashboard')">返回概览</el-button>
  </div>
</template>

<style scoped>
.not-found {
  height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
}

.not-found__code {
  margin: 0;
  font-size: 56px;
  font-weight: 700;
  color: #c4cad6;
}

.not-found__text {
  margin: 0 0 10px;
  color: var(--app-text-muted);
}
</style>
