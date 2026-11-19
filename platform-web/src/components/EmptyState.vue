<script setup lang="ts">
import { FolderOpened } from '@element-plus/icons-vue'

withDefaults(
  defineProps<{
    description?: string
    subDescription?: string
    compact?: boolean
  }>(),
  { description: '暂无数据', subDescription: '', compact: false }
)
</script>

<template>
  <div class="empty-state" :class="{ 'empty-state--compact': compact }">
    <el-icon class="empty-state__icon"><FolderOpened /></el-icon>
    <p class="empty-state__title">{{ description }}</p>
    <p v-if="subDescription" class="empty-state__sub">{{ subDescription }}</p>
    <div v-if="$slots.default" class="empty-state__action">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 42px 16px;
  color: var(--app-text-muted);
}

.empty-state--compact {
  padding: 18px 12px;
}

.empty-state__icon {
  font-size: 30px;
  color: #c4cad6;
}

.empty-state__title {
  margin: 0;
  font-size: 14px;
}

.empty-state__sub {
  margin: 0;
  font-size: 12px;
  max-width: 460px;
  text-align: center;
  line-height: 1.6;
}

.empty-state__action {
  margin-top: 10px;
}
</style>
