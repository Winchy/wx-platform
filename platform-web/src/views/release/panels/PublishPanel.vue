<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Checked, Promotion, Refresh, RefreshLeft, Upload } from '@element-plus/icons-vue'

import {
  cancelGray,
  getGrayPlan,
  getIcpStatus,
  grayFinish,
  grayRelease,
  publishOnline,
  type GrayPlan,
  type GrayReleaseParams,
  type IcpStatus,
  type ReleaseStatus
} from '@/api/release'
import { describeError, describeErrorDetail } from '@/utils/request'
import { formatDateTime } from '@/utils/format'
import { grayPercentageOptions } from '@/utils/status'

const props = defineProps<{ appid: string; status?: ReleaseStatus | string | null }>()

const emit = defineEmits<{ (e: 'changed'): void }>()

const busy = ref<'' | 'publish' | 'gray' | 'finish' | 'cancel'>('')
const errorText = ref('')
const grayPercentage = ref<number>(1)
const supportDebuggerFirst = ref(false)
const supportExperiencerFirst = ref(true)
const icp = ref<IcpStatus | null>(null)
const plan = ref<GrayPlan | null>(null)
const planLoading = ref(false)

/** 备案只是提示，读失败不该挡住发布面板，所以这里吞掉异常。 */
async function loadIcp(): Promise<void> {
  icp.value = null
  if (!props.appid) return
  try {
    icp.value = await getIcpStatus(props.appid)
  } catch {
    icp.value = null
  }
}

/** 灰度计划读不到时仍要能操作，只是按钮可用性只能退回本地发布单状态。 */
async function loadPlan(): Promise<void> {
  plan.value = null
  if (!props.appid) return
  planLoading.value = true
  try {
    plan.value = await getGrayPlan(props.appid)
    if (plan.value?.localCorrected) emit('changed')
  } catch {
    plan.value = null
  } finally {
    planLoading.value = false
  }
}

function reload(): void {
  void loadIcp()
  void loadPlan()
}

onMounted(reload)
watch(() => props.appid, reload)

const notFiled = computed(() => icp.value !== null && icp.value.filed === false)
const icpTitle = computed(() => {
  if (!icp.value) return ''
  const canceling = icp.value.canceling ? '（正在注销备案）' : ''
  return `小程序${icp.value.statusText}${canceling}，未备案的小程序无法发布上线`
})
const icpHints = computed(() => {
  const status = icp.value
  if (!status) return [] as string[]
  return [
    ...status.missingPrerequisites,
    ...status.rejectReasons.map(
      (item) =>
        `${item.key_name ?? '备案信息'}：${item.error ?? ''}${item.suggest ? `（建议：${item.suggest}）` : ''}`
    ),
    ...(status.entranceAvailable ? [] : ['微信侧暂未开放该小程序的备案入口，可稍后重试']),
  ]
})

const normalized = computed(() => String(props.status ?? '').toUpperCase())
const auditPassed = computed(() => normalized.value === 'AUDIT_PASS')
const inGray = computed(() => normalized.value === 'GRAY')
const published = computed(() => normalized.value === 'PUBLISHED')
/** 读到微信计划时以计划为准：本地发布单可能停在 GRAY，而灰度其实已经跑完或被删掉。 */
const grayActive = computed(() => (plan.value ? plan.value.actionable : inGray.value))
const staleGray = computed(() => inGray.value && plan.value !== null && !plan.value.actionable)
const canPublish = computed(() => Boolean(props.appid) && (auditPassed.value || grayActive.value))

const planSummary = computed(() => {
  if (!props.appid) return ''
  const value = plan.value
  if (!value) {
    return planLoading.value
      ? '正在读取微信侧灰度计划…'
      : '未读到微信侧灰度计划，下方按钮的可用性暂按本地发布单状态判断'
  }
  if (value.status === null) return '微信侧当前没有分阶段发布计划'
  const audience = [
    value.supportDebuggerFirst ? '开发者优先' : '',
    value.supportExperiencerFirst ? '体验成员优先' : ''
  ]
    .filter(Boolean)
    .join(' + ')
  const created = value.createdAt ? ` · 创建于 ${formatDateTime(value.createdAt)}` : ''
  return `微信侧灰度计划：${value.statusText} · ${value.grayPercentage}%${audience ? ` · ${audience}` : ''}${created}`
})

const statusHint = computed(() => {
  if (!props.appid) return '请先选择小程序。'
  if (auditPassed.value) return '审核已通过，可直接全量发布或先灰度发布观察。'
  if (grayActive.value) return '当前处于灰度发布中，可结束灰度（全量）或取消灰度。'
  if (inGray.value) return '本地发布单显示灰度中，但微信侧的灰度计划已不在执行中，请核对下方计划详情。'
  if (published.value) return '线上已是最新版本；如需发布新版本，请重新上传代码并提交审核。'
  if (normalized.value === 'AUDIT_REJECT') return '审核未通过，请修正后重新提交审核。'
  if (normalized.value === 'AUDITING') return '审核进行中，通过后才能发布。'
  return '尚无审核通过的发布单，请先上传代码并提交审核。'
})

function fail(action: string, error: unknown): void {
  const detail = describeErrorDetail(error)
  errorText.value = `${action}失败：${describeError(error)}${detail ? `（${detail}）` : ''}`
  ElMessage.error(errorText.value)
}

async function runPublish(): Promise<void> {
  if (!canPublish.value) {
    ElMessage.warning('当前状态不允许发布')
    return
  }
  try {
    await ElMessageBox.confirm('发布后线上用户将立即使用该版本，确认发布？', '发布上线', {
      type: 'warning',
      confirmButtonText: '确认发布',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  busy.value = 'publish'
  errorText.value = ''
  try {
    await publishOnline(props.appid)
    ElMessage.success('已发布上线')
    emit('changed')
    void loadPlan()
  } catch (error) {
    fail('发布上线', error)
  } finally {
    busy.value = ''
  }
}

async function runGray(): Promise<void> {
  const payload: GrayReleaseParams = {
    grayPercentage: grayPercentage.value,
    supportDebuggerFirst: supportDebuggerFirst.value,
    supportExperiencerFirst: supportExperiencerFirst.value
  }
  if (payload.grayPercentage === 0 && !payload.supportDebuggerFirst && !payload.supportExperiencerFirst) {
    ElMessage.warning('比例为 0 时，需开启「开发者优先」或「体验成员优先」之一')
    return
  }
  const audience =
    payload.grayPercentage === 0
      ? '只放给优先灰度人群（开发者/体验成员）'
      : payload.grayPercentage === 100
        ? '向全部用户（100%）'
        : `向约 ${payload.grayPercentage}% 的用户`
  try {
    await ElMessageBox.confirm(
      `将${audience}发布新版本，确认开启灰度发布？`,
      '灰度发布',
      { type: 'warning', confirmButtonText: '确认灰度', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  busy.value = 'gray'
  errorText.value = ''
  try {
    await grayRelease(props.appid, payload)
    ElMessage.success(`灰度发布已开启（${payload.grayPercentage}%）`)
    emit('changed')
    void loadPlan()
  } catch (error) {
    fail('灰度发布', error)
  } finally {
    busy.value = ''
  }
}

async function runGrayFinish(): Promise<void> {
  try {
    await ElMessageBox.confirm('结束灰度后，新版本将全量上线，确认继续？', '全量发布', {
      type: 'warning',
      confirmButtonText: '结束灰度',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  busy.value = 'finish'
  errorText.value = ''
  try {
    await grayFinish(props.appid)
    ElMessage.success('已结束灰度并全量发布')
    emit('changed')
    void loadPlan()
  } catch (error) {
    fail('结束灰度', error)
  } finally {
    busy.value = ''
  }
}

async function runGrayCancel(): Promise<void> {
  try {
    await ElMessageBox.confirm('取消灰度会回到灰度前的线上版本，确认取消？', '取消灰度', {
      type: 'warning',
      confirmButtonText: '取消灰度',
      cancelButtonText: '再想想'
    })
  } catch {
    return
  }
  busy.value = 'cancel'
  errorText.value = ''
  try {
    await cancelGray(props.appid)
    ElMessage.success('灰度已取消')
    emit('changed')
    void loadPlan()
  } catch (error) {
    fail('取消灰度', error)
  } finally {
    busy.value = ''
  }
}
</script>

<template>
  <div class="publish-panel">
    <el-alert type="info" show-icon :closable="false" :title="statusHint" class="mb-12" />

    <el-alert
      v-if="staleGray"
      type="warning"
      show-icon
      :closable="false"
      class="mb-12"
      title="本地发布单仍是「灰度中」，但微信侧的灰度计划已不在执行中"
      :description="`${planSummary}。放量请重新发起灰度；若已全量或已取消，请回到上一步刷新发布单状态。`"
    />
    <p v-else-if="appid" class="publish-panel__plan muted">
      {{ planSummary }}
      <el-button text size="small" :icon="Refresh" :loading="planLoading" @click="loadPlan()">
        重读灰度计划
      </el-button>
    </p>

    <el-alert
      v-if="notFiled"
      type="warning"
      show-icon
      :closable="false"
      :title="icpTitle"
      class="mb-12"
    >
      <ul v-if="icpHints.length" class="publish-panel__list">
        <li v-for="hint in icpHints" :key="hint">{{ hint }}</li>
      </ul>
    </el-alert>

    <div class="publish-panel__row">
      <el-button
        type="success"
        :icon="Promotion"
        :loading="busy === 'publish'"
        :disabled="!canPublish"
        @click="runPublish"
      >
        发布上线（全量）
      </el-button>

      <el-select v-model="grayPercentage" style="width: 200px" :disabled="!canPublish">
        <el-option v-for="item in grayPercentageOptions" :key="item.value" :value="item.value" :label="item.label" />
      </el-select>
      <el-button :icon="Upload" :loading="busy === 'gray'" :disabled="!canPublish" @click="runGray">
        灰度发布
      </el-button>
    </div>

    <div class="publish-panel__row">
      <el-switch v-model="supportDebuggerFirst" :disabled="!canPublish" active-text="开发者优先" />
      <el-switch v-model="supportExperiencerFirst" :disabled="!canPublish" active-text="体验成员优先" />
    </div>

    <div class="publish-panel__row">
      <el-button
        type="primary"
        plain
        :icon="Checked"
        :loading="busy === 'finish'"
        :disabled="!grayActive"
        @click="runGrayFinish"
      >
        结束灰度 / 全量放开
      </el-button>
      <el-button type="warning" plain :icon="RefreshLeft" :loading="busy === 'cancel'" :disabled="!grayActive" @click="runGrayCancel">
        取消灰度
      </el-button>
    </div>

    <el-alert
      v-if="errorText"
      class="mt-12"
      type="error"
      show-icon
      :closable="false"
      :title="errorText"
    />

    <p class="muted publish-panel__tip">
      灰度比例为 0~100 的整数，由微信随机分桶；选 0 时需开启「开发者优先」或「体验成员优先」，选 100 即灰度计划全量放开。
      灰度期间线上仍保留旧版本给未命中用户；如需回退请使用右侧「版本回退」。
    </p>
  </div>
</template>

<style scoped>
.mb-12 {
  margin-bottom: 12px;
}

.mt-12 {
  margin-top: 12px;
}

.publish-panel__plan {
  margin: -4px 0 10px;
  font-size: 12px;
  line-height: 1.7;
}

.publish-panel__row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}

.publish-panel__tip {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
}

.publish-panel__list {
  margin: 6px 0 0;
  padding-left: 18px;
  font-size: 12px;
  line-height: 1.7;
}
</style>
