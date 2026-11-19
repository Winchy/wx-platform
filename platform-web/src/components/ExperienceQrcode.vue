<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Link, Refresh } from '@element-plus/icons-vue'

import { experienceQrcodeDirectUrl, fetchExperienceQrcodeUrl } from '@/api/release'
import { describeError } from '@/utils/request'

const props = defineProps<{ appid: string }>()

const loading = ref(false)
const errorText = ref('')
const imageUrl = ref('')

const directUrl = () => experienceQrcodeDirectUrl(props.appid)

function release(): void {
  if (imageUrl.value.startsWith('blob:')) {
    URL.revokeObjectURL(imageUrl.value)
  }
  imageUrl.value = ''
}

async function load(): Promise<void> {
  if (!props.appid) return
  loading.value = true
  errorText.value = ''
  release()
  try {
    imageUrl.value = await fetchExperienceQrcodeUrl(props.appid)
  } catch (err) {
    errorText.value = describeError(err)
  } finally {
    loading.value = false
  }
}

async function onReload(): Promise<void> {
  await load()
  if (!errorText.value) {
    ElMessage.success('体验版二维码已刷新')
  }
}

function onImageError(): void {
  errorText.value = '图片加载失败：该接口需要登录态（Authorization 头），请用上方「刷新」按钮重新拉取，或在新窗口登录后直接访问直链。'
}

watch(() => props.appid, load)
load()

onBeforeUnmount(release)
defineExpose({ reload: load })
</script>

<template>
  <div class="exp-qrcode">
    <div v-loading="loading" class="exp-qrcode__box">
      <el-image
        v-if="imageUrl && !errorText"
        :src="imageUrl"
        fit="contain"
        class="exp-qrcode__img"
        @error="onImageError"
      />
      <div v-else class="exp-qrcode__placeholder">
        <el-icon><Link /></el-icon>
        <span>{{ errorText || '尚未获取体验版二维码' }}</span>
      </div>
    </div>

    <div class="exp-qrcode__actions">
      <el-button size="small" :loading="loading" :icon="Refresh" @click="onReload">刷新二维码</el-button>
      <el-link v-if="appid" :href="directUrl()" target="_blank" type="primary" :underline="false">
        在新窗口打开直链
      </el-link>
    </div>

    <p class="muted exp-qrcode__tip">
      体验版仅供已添加的体验成员扫码使用；重新上传代码后二维码内容会变化，建议重新获取。
    </p>
  </div>
</template>

<style scoped>
.exp-qrcode__box {
  width: 190px;
  height: 190px;
  border: 1px solid var(--app-border-color);
  border-radius: 8px;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
}

.exp-qrcode__img {
  width: 176px;
  height: 176px;
}

.exp-qrcode__placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 14px;
  color: var(--app-text-muted);
  font-size: 12px;
  text-align: center;
  line-height: 1.6;
}

.exp-qrcode__actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
}

.exp-qrcode__tip {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.6;
  max-width: 420px;
}
</style>
