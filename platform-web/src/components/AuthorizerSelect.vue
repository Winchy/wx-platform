<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'

import { fetchAuthorizerOptions, type AuthorizerItem } from '@/api/authorizer'
import { describeError } from '@/utils/request'

const appid = defineModel<string>({ default: '' })

const props = withDefaults(
  defineProps<{
    placeholder?: string
    /** 仅展示已授权小程序 */
    onlyAuthorized?: boolean
    clearable?: boolean
  }>(),
  { placeholder: '请选择小程序（可搜索名称或 AppID）', onlyAuthorized: false, clearable: false }
)

const emit = defineEmits<{ (e: 'change', value: AuthorizerItem | null): void }>()

const options = ref<AuthorizerItem[]>([])
const loading = ref(false)
const error = ref('')

const authorizedStates = ['AUTHORIZED', 'NORMAL']

function isAuthorized(item: AuthorizerItem): boolean {
  const state = (item.authState ?? '').toUpperCase()
  return state === '' || authorizedStates.includes(state)
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const page = await fetchAuthorizerOptions(200)
    const records = page?.records ?? []
    options.value = props.onlyAuthorized ? records.filter(isAuthorized) : records
    if (appid.value) {
      emit('change', options.value.find((item) => item.appid === appid.value) ?? null)
    }
  } catch (err) {
    error.value = describeError(err)
    options.value = []
  } finally {
    loading.value = false
  }
}

function onChange(value: string): void {
  emit('change', options.value.find((item) => item.appid === value) ?? null)
}

onMounted(load)

defineExpose({ reload: load, options })
</script>

<template>
  <div class="authorizer-select">
    <el-select
      v-model="appid"
      filterable
      :clearable="clearable"
      :loading="loading"
      :placeholder="error || placeholder"
      class="authorizer-select__control"
      @change="onChange"
    >
      <el-option v-for="item in options" :key="item.appid" :value="item.appid" :label="item.nickName || item.appid">
        <div class="authorizer-select__option">
          <span class="ellipsis">{{ item.nickName || '未命名小程序' }}</span>
          <span class="mono muted">{{ item.appid }}</span>
        </div>
      </el-option>
    </el-select>
    <el-button :loading="loading" text circle title="重新加载小程序列表" @click="load">
      <el-icon><Refresh /></el-icon>
    </el-button>
  </div>
</template>

<style scoped>
.authorizer-select {
  display: flex;
  align-items: center;
  gap: 4px;
}

.authorizer-select__control {
  width: 320px;
  max-width: 100%;
}

.authorizer-select__option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
</style>
