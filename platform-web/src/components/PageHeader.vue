<script setup lang="ts">
withDefaults(
  defineProps<{
    title: string
    subtitle?: string
    icon?: string
  }>(),
  { subtitle: '', icon: '' }
)
</script>

<template>
  <header class="page-header">
    <div class="page-header__main">
      <h1 class="page-header__title">
        <slot name="icon" />
        {{ title }}
      </h1>
      <p v-if="subtitle" class="page-header__subtitle">{{ subtitle }}</p>
    </div>
    <div class="page-header__extra">
      <slot name="extra" />
    </div>
  </header>
</template>

<style scoped>
.page-header {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 16px;
}

.page-header__main {
  flex: 1 1 240px;
  min-width: 0;
}

.page-header__title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 19px;
  font-weight: 600;
  color: #111827;
}

.page-header__subtitle {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--app-text-muted);
  line-height: 1.5;
}

.page-header__extra {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 0 1 auto;
  min-width: 0;
}
</style>
