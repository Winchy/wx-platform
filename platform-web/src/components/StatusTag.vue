<script setup lang="ts">
import { computed } from 'vue'

import { resolveStatus, type StatusKind } from '@/utils/status'

const props = withDefaults(
  defineProps<{
    kind: StatusKind
    value: string | number | boolean | null | undefined
    size?: 'large' | 'default' | 'small'
    effect?: 'dark' | 'light' | 'plain'
    /** 无匹配映射时的兜底文案 */
    emptyText?: string
  }>(),
  { size: 'small', effect: 'light', emptyText: '' }
)

const meta = computed(() => {
  if (props.value === null || props.value === undefined || props.value === '') {
    return { label: props.emptyText || resolveStatus(props.kind, props.value).label, type: 'info' as const }
  }
  return resolveStatus(props.kind, props.value)
})
</script>

<template>
  <el-tag :type="meta.type" :size="size" :effect="effect" round disable-transitions>
    {{ meta.label }}
  </el-tag>
</template>
