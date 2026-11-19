<script setup lang="ts">
import { computed } from 'vue'
import { CopyDocument } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

import { copyToClipboard, prettyJson } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    value: string | null | undefined
    /** 原始 JSON 字符串会被美化 */
    formatJson?: boolean
    maxHeight?: number
    emptyText?: string
    label?: string
  }>(),
  { formatJson: true, maxHeight: 240, emptyText: '无内容', label: '' }
)

const text = computed(() => {
  const raw = props.value ?? ''
  if (!raw) return ''
  return props.formatJson ? prettyJson(raw) : raw
})

async function onCopy(): Promise<void> {
  if (!text.value) {
    ElMessage.warning('没有可复制的内容')
    return
  }
  const ok = await copyToClipboard(text.value)
  if (ok) {
    ElMessage.success('已复制到剪贴板')
  } else {
    ElMessage.error('复制失败，请手动选择文本')
  }
}
</script>

<template>
  <div class="json-block">
    <div v-if="label || $slots.extra" class="json-block__head">
      <span class="strong">{{ label }}</span>
      <span class="json-block__head-right">
        <slot name="extra" />
        <el-button text size="small" :disabled="!text" @click="onCopy">
          <el-icon><CopyDocument /></el-icon>
          <span>复制</span>
        </el-button>
      </span>
    </div>
    <pre v-if="text" class="code-block json-block__pre" :style="{ maxHeight: `${maxHeight}px` }">{{ text }}</pre>
    <p v-else class="muted json-block__empty">{{ emptyText }}</p>
  </div>
</template>

<style scoped>
.json-block__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 6px;
  font-size: 13px;
}

.json-block__head-right {
  display: flex;
  align-items: center;
  gap: 6px;
}

.json-block__pre {
  margin: 0;
}

.json-block__empty {
  margin: 0;
  font-size: 12px;
}
</style>
