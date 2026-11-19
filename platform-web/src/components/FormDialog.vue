<script setup lang="ts">
import { computed } from 'vue'

const visible = defineModel<boolean>({ required: false, default: false })

const props = withDefaults(
  defineProps<{
    title: string
    width?: string | number
    top?: string
    confirmText?: string
    cancelText?: string
    loading?: boolean
    disabled?: boolean
    /** 是否展示底部按钮 */
    showFooter?: boolean
  }>(),
  {
    width: '560px',
    top: '10vh',
    confirmText: '确定',
    cancelText: '取消',
    loading: false,
    disabled: false,
    showFooter: true
  }
)

const emit = defineEmits<{
  (e: 'confirm'): void
  (e: 'cancel'): void
  (e: 'closed'): void
}>()

const dialogWidth = computed(() => (typeof props.width === 'number' ? `${props.width}px` : props.width))

function onConfirm(): void {
  emit('confirm')
}

function onCancel(): void {
  emit('cancel')
  visible.value = false
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="title"
    :width="dialogWidth"
    :top="top"
    append-to-body
    destroy-on-close
    align-center
    @closed="emit('closed')"
  >
    <div class="form-dialog__body">
      <slot />
    </div>
    <template v-if="showFooter" #footer>
      <div class="form-dialog__footer">
        <slot name="footer-left" />
        <el-button :disabled="loading" @click="onCancel">{{ cancelText }}</el-button>
        <el-button type="primary" :loading="loading" :disabled="disabled" @click="onConfirm">
          {{ confirmText }}
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.form-dialog__body {
  max-height: 62vh;
  overflow: auto;
  padding-right: 4px;
}

.form-dialog__footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
</style>
