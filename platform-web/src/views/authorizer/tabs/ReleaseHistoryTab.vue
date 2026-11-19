<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, RefreshLeft } from '@element-plus/icons-vue'

import {
  listReleaseHistory,
  listRevertVersions,
  revertRelease,
  type ReleaseRecord,
  type RevertParams
} from '@/api/release'
import { EmptyState, FormDialog, StatusTag } from '@/components'
import { displayValue, formatDateTime } from '@/utils/format'
import { describeError } from '@/utils/request'

interface RevertVersion {
  version: string
  desc: string
  time: string | number | null
}

const props = defineProps<{ appid: string; compact?: boolean }>()

const emit = defineEmits<{ (e: 'changed'): void }>()

const loading = ref(false)
const errorText = ref('')
const rows = ref<ReleaseRecord[]>([])

const revertVisible = ref(false)
const revertLoading = ref(false)
const versions = ref<RevertVersion[]>([])
const versionLoading = ref(false)
const selectedVersion = ref('')

const tableHeight = computed(() => (props.compact ? '320px' : 'auto'))

/** el-table 插槽只提供松类型的 row，这里用下标回到强类型数组 */
function rowAt(index: number): ReleaseRecord {
  return rows.value[index]
}

function textOf(record: Record<string, unknown>, keys: string[]): string {
  for (const key of keys) {
    const value = record[key]
    if (typeof value === 'string' && value.trim()) return value.trim()
    if (typeof value === 'number') return String(value)
  }
  return ''
}

/** 秒级时间戳换算成毫秒，交给 formatDateTime 处理 */
function timeOf(record: Record<string, unknown>, keys: string[]): string | number | null {
  for (const key of keys) {
    const value = record[key]
    if (typeof value === 'number' && Number.isFinite(value)) return value < 1e11 ? value * 1000 : value
    if (typeof value === 'string' && value.trim()) return value
  }
  return null
}

/** 微信原样返回，字段名可能是 app_version / user_version / version，逐个兜底 */
function normalizeVersions(raw: unknown): RevertVersion[] {
  let list: unknown[] = []
  if (Array.isArray(raw)) {
    list = raw
  } else if (raw && typeof raw === 'object') {
    const record = raw as Record<string, unknown>
    const nested = record.version_list ?? record.versionList ?? record.versions
    if (Array.isArray(nested)) list = nested
  }
  const result: RevertVersion[] = []
  for (const entry of list) {
    if (!entry || typeof entry !== 'object') {
      if (typeof entry === 'string' && entry.trim()) result.push({ version: entry.trim(), desc: '', time: null })
      continue
    }
    const record = entry as Record<string, unknown>
    const version = textOf(record, ['app_version', 'user_version', 'version', 'appVersion', 'userVersion'])
    if (!version) continue
    result.push({
      version,
      desc: textOf(record, ['user_desc', 'userDesc', 'desc']),
      time: timeOf(record, ['commit_time', 'commitTime', 'submitTime'])
    })
  }
  return result
}

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    const data = await listReleaseHistory(props.appid)
    rows.value = Array.isArray(data) ? data : []
  } catch (err) {
    errorText.value = describeError(err)
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function openRevert(): Promise<void> {
  revertVisible.value = true
  // 留空 = 回退到上一个线上版本
  selectedVersion.value = ''
  versionLoading.value = true
  try {
    versions.value = normalizeVersions(await listRevertVersions(props.appid))
  } catch (err) {
    versions.value = []
    ElMessage.error(describeError(err))
  } finally {
    versionLoading.value = false
  }
}

async function submitRevert(): Promise<void> {
  const params: RevertParams = { appVersion: selectedVersion.value || undefined }
  const target = params.appVersion || '上一个线上版本'
  try {
    await ElMessageBox.confirm(
      `线上版本将回退到 ${target}，用户端会立即生效，确认继续？`,
      '版本回退',
      { type: 'warning', confirmButtonText: '确认回退', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  revertLoading.value = true
  try {
    await revertRelease(props.appid, params)
    ElMessage.success('回退指令已下发')
    revertVisible.value = false
    await load()
    emit('changed')
  } catch (err) {
    ElMessage.error(describeError(err))
  } finally {
    revertLoading.value = false
  }
}

watch(() => props.appid, load)
onMounted(load)
defineExpose({ reload: load })
</script>

<template>
  <div class="release-history">
    <div class="release-history__toolbar">
      <el-button :loading="loading" size="small" :icon="Refresh" @click="load">刷新</el-button>
      <el-button size="small" type="warning" :icon="RefreshLeft" @click="openRevert">版本回退</el-button>
      <span v-if="errorText" class="muted">{{ errorText }}</span>
    </div>

    <el-table
      v-loading="loading"
      :data="rows"
      size="small"
      border
      :max-height="tableHeight"
      :empty-text="loading ? '加载中…' : '暂无发布记录'"
    >
      <el-table-column label="状态" width="110">
        <template #default="{ $index }: { $index: number }">
          <StatusTag kind="releaseStatus" :value="rowAt($index).status" />
        </template>
      </el-table-column>
      <el-table-column label="版本" width="90">
        <template #default="{ $index }: { $index: number }">
          <span class="mono">{{ displayValue(rowAt($index).userVersion, '—') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="描述" min-width="140" show-overflow-tooltip>
        <template #default="{ $index }: { $index: number }">{{ displayValue(rowAt($index).userDesc, '—') }}</template>
      </el-table-column>
      <el-table-column label="审核 ID" width="100">
        <template #default="{ $index }: { $index: number }">
          <span class="mono">{{ displayValue(rowAt($index).lastAuditId, '—') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="时间" :width="compact ? 160 : 180">
        <template #default="{ $index }: { $index: number }">
          <div class="flex-col" style="gap: 0">
            <span>{{ formatDateTime(rowAt($index).createdAt) }}</span>
            <span class="muted">更新 {{ formatDateTime(rowAt($index).updatedAt) }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column v-if="!compact" label="errcode / errmsg" min-width="180">
        <template #default="{ $index }: { $index: number }">
          <div class="flex-col" style="gap: 0">
            <span class="mono">{{ displayValue(rowAt($index).errcode, '—') }}</span>
            <span class="muted ellipsis">{{ displayValue(rowAt($index).errmsg, '') }}</span>
          </div>
        </template>
      </el-table-column>

      <template #empty>
        <EmptyState description="暂无发布历史" sub-description="每次上传代码、提交审核与发布都会在这里留痕。" />
      </template>
    </el-table>

    <FormDialog
      v-model="revertVisible"
      title="回退线上版本"
      confirm-text="确认回退"
      width="480px"
      :loading="revertLoading"
      @confirm="submitRevert"
    >
      <el-form label-width="110px">
        <el-form-item label="历史版本">
          <el-select
            v-model="selectedVersion"
            :loading="versionLoading"
            filterable
            clearable
            placeholder="不选择则回退到上一个线上版本"
            style="width: 100%"
          >
            <el-option value="" label="回退到上一个线上版本（不指定版本号）" />
            <el-option
              v-for="item in versions"
              :key="item.version"
              :value="item.version"
              :label="`${item.version}${item.desc ? ` · ${item.desc}` : ''}`"
            >
              <div class="flex-row" style="justify-content: space-between">
                <span class="mono">{{ item.version }}</span>
                <span class="muted">{{ item.time ? formatDateTime(item.time) : '' }}</span>
              </div>
            </el-option>
          </el-select>
        </el-form-item>
        <el-alert
          type="info"
          show-icon
          :closable="false"
          title="回退仅对已上线版本生效；灰度期间回退会结束灰度并恢复到选定版本。"
        />
        <p v-if="!versions.length && !versionLoading" class="muted">
          微信未返回可回退的历史版本列表，可直接确认回退到上一个线上版本。
        </p>
      </el-form>
    </FormDialog>
  </div>
</template>

<style scoped>
.release-history__toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
</style>
