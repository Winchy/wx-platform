<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Lightning, Refresh, Search, Warning } from '@element-plus/icons-vue'

import {
  getAuditScreenshots,
  getLatestAudit,
  refreshAudit,
  speedupAudit,
  undoAudit,
  type AuditRecord,
  type AuditScreenshot
} from '@/api/release'
import { JsonBlock, StatusTag } from '@/components'
import { displayValue, formatDateTime } from '@/utils/format'
import { describeError } from '@/utils/request'

const props = defineProps<{ appid: string; auditId?: string }>()

const emit = defineEmits<{ (e: 'changed'): void }>()

const loading = ref(false)
const querying = ref(false)
const autoQuerying = ref(false)
const undoing = ref(false)
const speedingUp = ref(false)
const errorText = ref('')
const record = ref<AuditRecord | null>(null)
const manualAuditId = ref('')

const effectiveAuditId = computed(() => manualAuditId.value || String(record.value?.auditId ?? props.auditId ?? ''))
const rejected = computed(() => Number(record.value?.status) === 1)
const pending = computed(() => Number(record.value?.status) === 2)
/** 4 = 审核延后：审核单还在微信的队列里，只是结论被推迟，所以仍算「没审完」。 */
const delayed = computed(() => Number(record.value?.status) === 4)
const awaiting = computed(() => pending.value || delayed.value)

/** 审核要几小时到几天，靠人工点查询不现实；只要还没出结果就每 30 秒问一次微信。 */
const POLL_SECONDS = 30
const autoPolling = ref(true)
const secondsLeft = ref(POLL_SECONDS)
let timer: number | undefined

const pollable = computed(() => awaiting.value && autoPolling.value)
const pollHint = computed(() =>
  typeof document !== 'undefined' && document.hidden
    ? '页面在后台，已暂停自动查询'
    : `${secondsLeft.value} 秒后自动查询`)

/** 微信对 status=4 会带延后原因，有就原文展示，没有才给通用解释 */
const delayedHint = computed(() => {
  const reason = record.value?.reason
  return reason
    ? `微信返回的原因：${reason}。延后不等于驳回，审核单仍在队列里，按原因补充材料后等待即可，无需重新提审。`
    : '微信未返回延后原因。延后不等于驳回，审核单仍在队列里，常见于材料或资质需进一步核验、节假日审核量集中。'
})

const shots = ref<AuditScreenshot[]>([])
const shotLoading = ref(false)
const shotError = ref('')

/** 只有驳回才有截图，进页面不顺手多发几个素材请求。 */
async function loadScreenshots(): Promise<void> {
  if (!props.appid || !rejected.value) {
    shots.value = []
    return
  }
  shotLoading.value = true
  shotError.value = ''
  try {
    shots.value = await getAuditScreenshots(props.appid)
  } catch (err) {
    shotError.value = describeError(err)
    shots.value = []
  } finally {
    shotLoading.value = false
  }
}

watch(rejected, (now) => {
  if (now) void loadScreenshots()
  else shots.value = []
})

async function load(): Promise<void> {
  if (!props.appid) return
  loading.value = true
  errorText.value = ''
  try {
    record.value = await getLatestAudit(props.appid)
  } catch (err) {
    errorText.value = describeError(err)
    record.value = null
  } finally {
    loading.value = false
  }
}

async function onQuery(silent = false): Promise<void> {
  if (!props.appid || querying.value || autoQuerying.value) return
  if (silent) autoQuerying.value = true
  else querying.value = true
  errorText.value = ''
  try {
    const before = Number(record.value?.status)
    // 不传 auditId 时后端刷新最近一次提审单
    record.value = await refreshAudit(props.appid, effectiveAuditId.value || undefined)
    const after = Number(record.value?.status)
    const wasAwaiting = before === 2 || before === 4
    if (wasAwaiting && (after === 0 || after === 1 || after === 3)) {
      ElMessage.success(
        after === 0 ? '审核已通过，可以发布或灰度了'
          : after === 1 ? '审核未通过，请看「审核结果」里的原因'
            : '审核单已撤回，需要重新提交审核')
    } else if (after === 4 && before !== 4) {
      ElMessage.warning('微信延后了本次审核，原因见下方审核详情')
    } else if (!silent) {
      ElMessage.success('已向微信侧查询最新审核状态')
    }
    emit('changed')
  } catch (err) {
    errorText.value = describeError(err)
  } finally {
    querying.value = false
    autoQuerying.value = false
    // 刚查过就重新倒数，避免手动查完紧接着又自动查一次
    if (pollable.value) secondsLeft.value = POLL_SECONDS
  }
}

function stopPolling(): void {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
}

function startPolling(): void {
  stopPolling()
  if (!pollable.value) return
  secondsLeft.value = POLL_SECONDS
  timer = window.setInterval(() => {
    // 切到别的标签页就不打扰微信，回来后继续倒计时
    if (typeof document !== 'undefined' && document.hidden) return
    secondsLeft.value -= 1
    if (secondsLeft.value > 0) return
    secondsLeft.value = POLL_SECONDS
    void onQuery(true)
  }, 1000)
}

watch(pollable, (on) => (on ? startPolling() : stopPolling()), { immediate: true })

onBeforeUnmount(stopPolling)

async function onSpeedup(): Promise<void> {
  if (!props.appid) return
  const target = effectiveAuditId.value || '最近一次提审'
  try {
    await ElMessageBox.confirm(
      `将消耗一次加急额度，对审核单 ${target} 做加急处理，微信侧预计 2-12 小时内审完。确认加速？`,
      '加速审核',
      { type: 'warning', confirmButtonText: '确认加速', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  speedingUp.value = true
  errorText.value = ''
  try {
    await speedupAudit(props.appid, effectiveAuditId.value || undefined)
    ElMessage.success('加急已提交，稍后可点击「主动查询」获取最新结果')
    await load()
    emit('changed')
  } catch (err) {
    errorText.value = describeError(err)
  } finally {
    speedingUp.value = false
  }
}

async function onUndo(): Promise<void> {
  if (!props.appid) return
  try {
    await ElMessageBox.confirm(
      '撤回后本次审核作废，需要重新提交审核（会占用当月审核次数）。确认撤回？',
      '撤回审核',
      { type: 'warning', confirmButtonText: '撤回', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  undoing.value = true
  errorText.value = ''
  try {
    await undoAudit(props.appid)
    ElMessage.success('审核已撤回')
    await load()
    emit('changed')
  } catch (err) {
    errorText.value = describeError(err)
  } finally {
    undoing.value = false
  }
}

watch(() => [props.appid, props.auditId], () => {
  manualAuditId.value = props.auditId ?? ''
  void load()
})
onMounted(load)
defineExpose({ reload: load })
</script>

<template>
  <div v-loading="loading" class="audit-status">
    <el-alert v-if="errorText" type="error" show-icon :closable="false" :title="errorText" class="mb-12" />

    <template v-if="record">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="审核状态">
          <StatusTag kind="auditStatus" :value="record.status" />
        </el-descriptions-item>
        <el-descriptions-item label="auditId">
          <span class="mono">{{ displayValue(record.auditId) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="提交时间">{{ formatDateTime(record.submittedAt) }}</el-descriptions-item>
        <el-descriptions-item label="最近查询">{{ formatDateTime(record.lastQueriedAt) }}</el-descriptions-item>
      </el-descriptions>

      <el-alert
        v-if="rejected"
        class="mt-12"
        type="error"
        show-icon
        :closable="false"
        title="审核未通过，请按以下原因修改后重新提交"
        :description="displayValue(record.reason, '微信侧未返回具体原因，请查看下方审核详情')"
      />
      <el-alert
        v-else-if="pending"
        class="mt-12"
        type="warning"
        show-icon
        :closable="false"
        title="审核进行中"
        description="通常 1-7 个工作日。页面留着即可，系统每 30 秒自动向微信查询一次，出结果会提示；也可以点「主动查询」立刻查，或有加急额度时点「加速审核」（加急后预计 2-12 小时内审完）。"
      />
      <el-alert
        v-else-if="delayed"
        class="mt-12"
        type="warning"
        show-icon
        :closable="false"
        title="审核延后"
        :description="delayedHint"
      />

      <div v-if="rejected" v-loading="shotLoading" class="audit-status__shots mt-12">
        <el-alert v-if="shotError" type="warning" show-icon :closable="false" :title="shotError" />
        <template v-else-if="shots.length">
          <p class="muted">微信给出的驳回截图 {{ shots.length }} 张，点开可放大查看：</p>
          <div class="audit-status__shots-grid">
            <el-image
              v-for="(shot, index) in shots"
              :key="shot.mediaId"
              class="audit-status__shot"
              :src="shot.dataUrl"
              :preview-src-list="shots.map((item) => item.dataUrl)"
              :initial-index="index"
              :alt="`驳回截图 ${index + 1}`"
              fit="cover"
              preview-teleported
            />
          </div>
        </template>
        <p v-else-if="!shotLoading" class="muted">微信本次没有返回驳回截图，请按上方原因自查后重新提审。</p>
      </div>

      <JsonBlock
        v-if="record.detailJson"
        class="mt-12"
        label="审核详情 detailJson"
        :value="record.detailJson"
        :max-height="220"
      />
    </template>

    <el-empty v-else-if="!loading" description="暂无审核记录">
      <template #image>
        <el-icon :size="34"><Warning /></el-icon>
      </template>
    </el-empty>

    <div class="audit-status__actions">
      <el-input
        v-model="manualAuditId"
        placeholder="手动指定 auditId（选填）"
        clearable
        style="width: 240px"
      />
      <el-button :loading="querying" :icon="Search" @click="onQuery()">主动查询</el-button>
      <el-button :loading="loading" :icon="Refresh" text @click="load">读本地最新</el-button>
      <el-button type="primary" plain :loading="speedingUp" :disabled="!pending" :icon="Lightning" @click="onSpeedup">
        加速审核
      </el-button>
      <el-button type="warning" plain :loading="undoing" :disabled="!awaiting" @click="onUndo">撤回审核</el-button>
      <div v-if="awaiting" class="audit-status__poll">
        <el-switch v-model="autoPolling" :loading="autoQuerying" />
        <span class="muted">{{ pollable ? pollHint : '自动查询已关闭，需手动点「主动查询」' }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mb-12 {
  margin-bottom: 12px;
}

.mt-12 {
  margin-top: 12px;
}

.audit-status__actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 14px;
}

.audit-status__poll {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
  font-size: 12px;
}

.audit-status__shots {
  min-height: 96px;
}

.audit-status__shots-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 8px;
}

.audit-status__shot {
  width: 120px;
  height: 200px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  cursor: zoom-in;
}
</style>
