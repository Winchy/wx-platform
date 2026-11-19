<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Promotion } from '@element-plus/icons-vue'

import {
  getLatestAudit,
  getLatestRelease,
  getVisitStatus,
  setVisitStatus,
  type AuditRecord,
  type ReleaseRecord
} from '@/api/release'
import { EmptyState, JsonBlock, StatusTag } from '@/components'
import { displayValue, formatDateTime, prettyJson } from '@/utils/format'
import { describeError } from '@/utils/request'

const props = defineProps<{ appid: string }>()

const router = useRouter()
const loading = ref(false)
const error = ref('')
const release = ref<ReleaseRecord | null>(null)
const audit = ref<AuditRecord | null>(null)
const visitStatus = ref<number | null>(null)

const auditDetailText = computed(() => prettyJson(audit.value?.detailJson ?? ''))

/** 微信：1 未暂停服务，0 已暂停服务（主动暂停或违规暂停都算 0）。 */
const serviceState = computed(() => {
  if (visitStatus.value === 1) return { text: '服务正常', type: 'success' as const }
  if (visitStatus.value === 0) return { text: '已暂停服务（主动暂停或违规暂停）', type: 'danger' as const }
  return { text: '服务状态未知', type: 'info' as const }
})

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [latestRelease, latestAudit, status] = await Promise.all([
      getLatestRelease(props.appid),
      getLatestAudit(props.appid).catch(() => null),
      getVisitStatus(props.appid).catch(() => null)
    ])
    release.value = latestRelease
    audit.value = latestAudit
    visitStatus.value = status
  } catch (err) {
    error.value = describeError(err)
    release.value = null
    audit.value = null
  } finally {
    loading.value = false
  }
}

watch(() => props.appid, load)
onMounted(load)
defineExpose({ reload: load })

const visitSaving = ref(false)

async function onSetVisitStatus(action: 'open' | 'close'): Promise<void> {
  const label = action === 'close' ? '暂停服务' : '恢复服务'
  const tip = action === 'close'
    ? '暂停后会员立刻打不开该小程序，直到恢复为止。确定暂停？'
    : '确定恢复服务？若因违规被微信暂停，这里恢复不了。'
  try {
    await ElMessageBox.confirm(tip, `${label}确认`, { type: 'warning', confirmButtonText: `确定${label}` })
  } catch {
    return
  }
  visitSaving.value = true
  try {
    const next = await setVisitStatus(props.appid, action)
    visitStatus.value = next
    if (next === 0) ElMessage.success('已暂停服务')
    else if (next === 1) ElMessage.success('服务已恢复')
    else ElMessage.warning(`${label}请求已发出，但状态读取失败，请刷新确认`)
  } catch (err) {
    ElMessage.error(describeError(err))
  } finally {
    visitSaving.value = false
  }
}
</script>

<template>
  <div v-loading="loading" class="release-status">
    <el-alert v-if="error" type="error" show-icon :closable="false" :title="error" class="mb-14" />

    <div class="release-status__actions">
      <el-button :loading="loading" :icon="Refresh" @click="load">刷新状态</el-button>
      <el-button type="primary" :icon="Promotion" @click="router.push({ path: '/release', query: { appid } })">
        打开发布工作台
      </el-button>
      <span class="muted">服务状态：</span>
      <el-tag :type="serviceState.type" effect="plain">{{ serviceState.text }}</el-tag>
      <el-button
        v-if="visitStatus === 0 || visitStatus === 1"
        :loading="visitSaving"
        :type="visitStatus === 1 ? 'warning' : 'success'"
        plain
        @click="onSetVisitStatus(visitStatus === 1 ? 'close' : 'open')"
      >
        {{ visitStatus === 1 ? '暂停服务' : '恢复服务' }}
      </el-button>
    </div>

    <template v-if="release">
      <div class="grid-two-col">
        <section class="panel panel--flat">
          <h3 class="panel__title">当前发布单</h3>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="发布状态">
              <StatusTag kind="releaseStatus" :value="release.status" />
            </el-descriptions-item>
            <el-descriptions-item label="发布单 ID">{{ displayValue(release.id) }}</el-descriptions-item>
            <el-descriptions-item label="版本号">{{ displayValue(release.userVersion, '未填写') }}</el-descriptions-item>
            <el-descriptions-item label="版本描述">{{ displayValue(release.userDesc, '未填写') }}</el-descriptions-item>
            <el-descriptions-item label="模板 / 草稿">
              <span class="mono">
                {{ release.templateId ? `template ${release.templateId}` : release.draftId ? `draft ${release.draftId}` : '—' }}
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="最近审核 ID">
              {{ displayValue(release.lastAuditId, '尚未提交审核') }}
            </el-descriptions-item>
            <el-descriptions-item label="创建 / 更新">
              {{ formatDateTime(release.createdAt) }} · {{ formatDateTime(release.updatedAt) }}
            </el-descriptions-item>
          </el-descriptions>

          <el-alert
            v-if="release.auditReason"
            class="mt-12"
            type="error"
            show-icon
            :closable="false"
            title="驳回原因"
            :description="release.auditReason"
          />
          <el-alert
            v-if="release.errcode || release.errmsg"
            class="mt-12"
            type="warning"
            show-icon
            :closable="false"
            :title="`接口异常 errcode=${displayValue(release.errcode, '—')}`"
            :description="displayValue(release.errmsg, '')"
          />
        </section>

        <section class="panel panel--flat">
          <h3 class="panel__title">最近一次审核</h3>
          <template v-if="audit">
            <el-descriptions :column="1" border size="small">
              <el-descriptions-item label="审核状态">
                <StatusTag kind="auditStatus" :value="audit.status" />
              </el-descriptions-item>
              <el-descriptions-item label="auditId">{{ displayValue(audit.auditId) }}</el-descriptions-item>
              <el-descriptions-item label="驳回 / 说明">{{ displayValue(audit.reason, '无') }}</el-descriptions-item>
              <el-descriptions-item label="提交时间">{{ formatDateTime(audit.submittedAt) }}</el-descriptions-item>
              <el-descriptions-item label="最近查询">{{ formatDateTime(audit.lastQueriedAt) }}</el-descriptions-item>
            </el-descriptions>
            <JsonBlock
              v-if="auditDetailText"
              class="mt-12"
              label="审核详情 detailJson"
              :value="audit.detailJson"
              :max-height="220"
            />
          </template>
          <EmptyState v-else description="暂无审核记录" sub-description="提交审核后这里会展示 auditId 与审核状态" />
        </section>
      </div>
    </template>

    <EmptyState
      v-else-if="!loading"
      description="该小程序还没有发布单"
      sub-description="在发布工作台选择代码模板并上传代码后，会生成第一条发布记录。"
    >
      <el-button type="primary" :icon="Promotion" @click="router.push({ path: '/release', query: { appid } })">
        去上传代码
      </el-button>
    </EmptyState>
  </div>
</template>

<style scoped>
.mb-14 {
  margin-bottom: 14px;
}

.mt-12 {
  margin-top: 12px;
}

.release-status__actions {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
}

.panel--flat {
  padding: 14px 16px;
}
</style>
