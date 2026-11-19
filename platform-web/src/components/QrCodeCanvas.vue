<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Warning } from '@element-plus/icons-vue'
import QRCode from 'qrcode'

import { describeError } from '@/utils/request'

const props = withDefaults(
  defineProps<{
    text: string
    size?: number
    /** 文本为空时的提示 */
    emptyText?: string
  }>(),
  { size: 240, emptyText: '暂无内容' }
)

const canvas = ref<HTMLCanvasElement | null>(null)
const error = ref('')
const boxStyle = computed(() => `${props.size}px`)

async function render(): Promise<void> {
  if (!props.text) {
    error.value = ''
    return
  }
  const target = canvas.value
  if (!target) return
  try {
    await QRCode.toCanvas(target, props.text, {
      width: props.size,
      margin: 2,
      errorCorrectionLevel: 'M',
      color: { dark: '#111827', light: '#ffffff' }
    })
    error.value = ''
  } catch (err) {
    error.value = describeError(err)
  }
}

onMounted(() => {
  void render()
})

watch(
  () => [props.text, props.size],
  () => {
    void render()
  },
  { flush: 'post' }
)

onBeforeUnmount(() => {
  const target = canvas.value
  const ctx = target?.getContext('2d')
  if (target && ctx) {
    ctx.clearRect(0, 0, target.width, target.height)
  }
})
</script>

<template>
  <div class="qr-code" :style="{ width: boxStyle }">
    <canvas v-show="Boolean(text) && !error" ref="canvas" class="qr-code__canvas" />
    <div v-if="!text" class="qr-code__placeholder">
      <el-icon><Warning /></el-icon>
      <span>{{ emptyText }}</span>
    </div>
    <div v-else-if="error" class="qr-code__placeholder qr-code__placeholder--error">
      <el-icon><Warning /></el-icon>
      <span>二维码生成失败：{{ error }}</span>
    </div>
    <slot />
  </div>
</template>

<style scoped>
.qr-code {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}

.qr-code__canvas {
  width: v-bind(boxStyle);
  height: v-bind(boxStyle);
  border: 1px solid var(--app-border-color);
  border-radius: 8px;
  background: #fff;
}

.qr-code__placeholder {
  width: v-bind(boxStyle);
  height: v-bind(boxStyle);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px dashed var(--app-border-color);
  border-radius: 8px;
  color: var(--app-text-muted);
  font-size: 13px;
  text-align: center;
  padding: 12px;
}

.qr-code__placeholder--error {
  border-color: var(--el-color-danger);
  color: var(--el-color-danger);
}
</style>
