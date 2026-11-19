<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Connection, Promotion, Refresh, Right } from '@element-plus/icons-vue'

import {
  getAuthorizer,
  refreshAuthorizerToken,
  type AuthorizerDetail
} from '@/api/authorizer'
import {
  getCodeInfo,
  getLatestRelease,
  type ReleaseRecord,
  type VersionInfoResult
} from '@/api/release'
import { AuthorizerSelect, EmptyState, ExperienceQrcode, PageHeader, StatusTag } from '@/components'
import ReleaseHistoryTab from '@/views/authorizer/tabs/ReleaseHistoryTab.vue'
import AuditStatusPanel from '@/views/release/panels/AuditStatusPanel.vue'
import AuditSubmitPanel from '@/views/release/panels/AuditSubmitPanel.vue'
import CommitPanel from '@/views/release/panels/CommitPanel.vue'
import PublishPanel from '@/views/release/panels/PublishPanel.vue'
import { displayValue, formatDateTime } from '@/utils/format'
import { releaseStepIndex, resolveStatus } from '@/utils/status'
import { describeError } from '@/utils/request'

const route = useRoute()
const router = useRouter()

const steps = ['上传代码', '体验版验证', '提交审核', '审核结果', '发布 / 灰度', '线上版本']

/** 还没过审的发布单状态，其余都属于「已过审，进度看发布/线上步骤」 */
const BEFORE_AUDIT_PASS = ['INIT', 'COMMITTED', 'AUDITING', 'AUDIT_REJECT']

/** 未过审时，哪一步能看到原因或下一步该做什么 */
const AUDIT_REASON_STEP: Record<string, number> = {
  INIT: 0,
  COMMITTED: 2,
  AUDITING: 3,
  AUDIT_REJECT: 3
}

const appid = ref<string>(typeof route.query.appid === 'string' ? route.query.appid : '')
const initialTemplateId = ref<string>(typeof route.query.templateId === 'string' ? route.query.templateId : '')
const detail = ref<AuthorizerDetail | null>(null)
const release = ref<ReleaseRecord | null>(null)
/** 微信侧的真实版本。release 只是我们自己记的账，两者可能不一致 */
const wxVersion = ref<VersionInfoResult | null>(null)
const auditId = ref('')
const activeStep = ref(0)
const loading = ref(false)
const errorText = ref('')
const tokenRefreshing = ref(false)
const stepTouched = ref(false)

const derivedStep = computed(() => releaseStepIndex(release.value?.status))
const currentStep = computed(() => (stepTouched.value ? activeStep.value : derivedStep.value))

const stepStatus = computed<'process' | 'finish'>(() => (release.value ? 'finish' : 'process'))

/** 微信把 commit / 提审 / 发布 都归到权限集 18，缺了整条链路只会报 48001 */
const funcScopeIds = computed(() => (detail.value?.funcScopes ?? '').split(',').filter(Boolean))
const missingReleaseScope = computed(() => funcScopeIds.value.length > 0 && !funcScopeIds.value.includes('18'))

/**
 * 本地发布单与微信侧线上版对不上时点名。灰度中不参与比对：灰度期间线上跑的还是灰度前那个版本，
 * release_info 回的就是它，跟本地 GRAY 状态比只会误报。
 */
const versionDrift = computed(() => {
  if (!appid.value || !wxVersion.value) return ''
  const status = String(release.value?.status ?? '').toUpperCase()
  if (status === 'GRAY') return ''
  const online = wxVersion.value.release?.version ?? ''
  if (status === 'PUBLISHED' && !online) {
    return '本地发布单显示「已上线」，但微信侧读不到线上版本，请到小程序后台确认真实状态。'
  }
  if (status !== 'PUBLISHED' && online) {
    return `微信侧线上已是 ${online}，本地发布单却未标记为已上线，可能是直接在小程序后台发布的。`
  }
  return ''
})

async function loadAll(): Promise<void> {
  if (!appid.value) {
    detail.value = null
    release.value = null
    wxVersion.value = null
    return
  }
  loading.value = true
  errorText.value = ''
  try {
    const [info, latest, versions] = await Promise.all([
      getAuthorizer(appid.value).catch((error: unknown) => {
        errorText.value = describeError(error)
        return null
      }),
      getLatestRelease(appid.value),
      // 微信侧版本读不到只影响这两行展示，不该拖垮整个工作台
      getCodeInfo(appid.value).catch(() => null)
    ])
    detail.value = info
    release.value = latest
    wxVersion.value = versions
    if (!stepTouched.value) {
      activeStep.value = derivedStep.value
    }
  } catch (error) {
    errorText.value = describeError(error)
    release.value = null
    wxVersion.value = null
  } finally {
    loading.value = false
  }
}

function onStepClick(index: number): void {
  stepTouched.value = true
  activeStep.value = index
}

function onBackToAuto(): void {
  stepTouched.value = false
  activeStep.value = derivedStep.value
}

function onCommitted(result: ReleaseRecord): void {
  auditId.value = ''
  release.value = result
  stepTouched.value = true
  activeStep.value = 1
  void loadAll()
}

function onAuditSubmitted(id: string): void {
  auditId.value = id
  stepTouched.value = true
  activeStep.value = 3
  void loadAll()
}

/** 权限集只能由小程序管理员在授权页重新勾选，这里带到扫码授权页并锁定当前小程序 */
function onReauthorize(): void {
  void router.push({
    path: '/authorizers/new',
    query: appid.value ? { appid: appid.value } : {}
  })
}

async function onRefreshToken(): Promise<void> {
  if (!appid.value) return
  tokenRefreshing.value = true
  try {
    const result = await refreshAuthorizerToken(appid.value)
    ElMessage.success(`authorizer_access_token 已刷新，有效期 ${result.expiresIn} 秒`)
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    tokenRefreshing.value = false
  }
}

async function onApplyReleaseStep(): Promise<void> {
  if (!release.value) return
  const status = String(release.value.status ?? '').toUpperCase()
  const label = resolveStatus('releaseStatus', status).label

  // 已过审的发布单不存在「尚未审核通过」，直接带到它当前该看的步骤
  if (!BEFORE_AUDIT_PASS.includes(status)) {
    stepTouched.value = true
    activeStep.value = status === 'PUBLISHED' ? 5 : 4
    ElMessage.info(`当前发布单为「${label}」，已跳转到对应步骤`)
    return
  }

  try {
    await ElMessageBox.confirm(`当前发布单为「${label}」，尚未审核通过，可跳转到对应步骤查看原因。`, '发布提示', {
      type: 'info',
      confirmButtonText: '查看原因',
      cancelButtonText: '知道了'
    })
    stepTouched.value = true
    activeStep.value = AUDIT_REASON_STEP[status] ?? 2
  } catch {
    // 用户取消
  }
}

watch(appid, (value) => {
  stepTouched.value = false
  activeStep.value = 0
  auditId.value = ''
  void router.replace({ path: '/release', query: value ? { appid: value } : {} })
  initialTemplateId.value = ''
  void loadAll()
})

watch(
  () => route.query.appid,
  (value) => {
    if (typeof value === 'string' && value && value !== appid.value) {
      appid.value = value
    }
  }
)

onMounted(() => {
  void loadAll()
})
</script>

<template>
  <div class="page">
    <div class="page__inner">
      <PageHeader title="发布工作台" subtitle="选中小程序后按步骤完成：上传代码 → 体验版 → 提交审核 → 审核 → 发布/灰度 → 线上">
        <template #icon>
          <el-icon><Promotion /></el-icon>
        </template>
        <template #extra>
          <AuthorizerSelect v-model="appid" only-authorized placeholder="选择要发布的小程序" />
          <el-button :loading="loading" text circle title="重新加载发布单" @click="loadAll">
            <el-icon><Refresh /></el-icon>
          </el-button>
        </template>
      </PageHeader>

      <el-alert v-if="errorText" type="warning" show-icon :closable="false" :title="errorText" class="mb-14" />

      <div class="workbench__layout">
        <div class="workbench__main">
          <section class="panel">
            <div class="workbench__summary">
              <div class="workbench__summary-main">
                <div class="workbench__app">
                  <el-avatar :size="36" :src="detail?.headImg ?? undefined">
                    {{ (detail?.nickName || appid || '未').slice(0, 1) }}
                  </el-avatar>
                  <div class="flex-col" style="gap: 0">
                    <span class="strong">{{ detail?.nickName || '未选择小程序' }}</span>
                    <span class="mono muted">{{ appid || '—' }}</span>
                  </div>
                </div>

                <el-divider direction="vertical" />

                <div class="workbench__kv">
                  <span class="muted">当前发布单</span>
                  <StatusTag kind="releaseStatus" :value="release?.status" empty-text="尚无发布单" />
                </div>
                <div class="workbench__kv">
                  <span class="muted">待发布版本</span>
                  <span class="mono strong">{{ displayValue(release?.userVersion, '—') }}</span>
                </div>
                <div class="workbench__kv">
                  <span class="muted">线上版本</span>
                  <span class="mono">{{ displayValue(detail?.releaseVersion, '未发布') }}</span>
                </div>
                <div class="workbench__kv">
                  <span class="muted">最近审核 ID</span>
                  <span class="mono">{{ displayValue(release?.lastAuditId, '—') }}</span>
                </div>
                <div class="workbench__kv">
                  <span class="muted">授权权限集</span>
                  <span class="mono">{{ funcScopeIds.length ? funcScopeIds.join(' / ') : '未获取' }}</span>
                </div>
              </div>

              <div class="workbench__summary-actions">
                <el-button size="small" text type="primary" :loading="loading" @click="loadAll">刷新状态</el-button>
                <el-button size="small" text :loading="tokenRefreshing" @click="onRefreshToken">刷新令牌</el-button>
                <el-button
                  size="small"
                  text
                  type="primary"
                  :disabled="!appid"
                  @click="router.push({ path: '/authorizers/' + appid, query: { tab: 'release' } })"
                >
                  小程序详情
                </el-button>
                <el-button v-if="!appid" size="small" type="primary" :icon="Connection" @click="router.push('/authorizers/new')">
                  去接入小程序
                </el-button>
              </div>
            </div>

            <el-alert
              v-if="missingReleaseScope"
              class="mb-14"
              type="warning"
              show-icon
              :closable="false"
              title="当前授权未包含权限集 18"
            >
              <p class="workbench__alert-text">
                上传代码、提交审核、发布与灰度都归属该权限集，调用会报 48001；请让小程序管理员重新授权并勾选它。
              </p>
              <el-button size="small" type="warning" plain @click="onReauthorize">重新授权</el-button>
            </el-alert>

            <el-steps
              :active="currentStep"
              finish-status="success"
              class="workbench__steps"
              @click.stop
            >
              <el-step
                v-for="(title, index) in steps"
                :key="title"
                :title="title"
                :status="index < currentStep ? 'finish' : index === currentStep ? stepStatus : 'wait'"
                class="workbench__step"
                @click="onStepClick(index)"
              />
            </el-steps>

            <div class="workbench__step-bar">
              <span class="muted">
                第 {{ currentStep + 1 }} / {{ steps.length }} 步：{{ steps[currentStep] }}
                <template v-if="!stepTouched">（按发布单状态自动定位）</template>
              </span>
              <el-button v-if="stepTouched" text size="small" @click="onBackToAuto">回到当前进度</el-button>
              <el-button
                v-if="currentStep < steps.length - 1"
                text
                type="primary"
                size="small"
                @click="onStepClick(currentStep + 1)"
              >
                下一步
                <el-icon><Right /></el-icon>
              </el-button>
            </div>
          </section>

          <section v-if="!appid" class="panel">
            <EmptyState
              description="请选择要操作的小程序"
              sub-description="只有已完成扫码授权的小程序才能上传代码并提交审核。"
            >
              <el-button type="primary" :icon="Connection" @click="router.push('/authorizers/new')">
                生成授权二维码
              </el-button>
            </EmptyState>
          </section>

          <template v-else>
            <section v-show="currentStep === 0" class="panel step-card">
              <h3 class="panel__title">步骤一 · 上传代码到体验版</h3>
              <CommitPanel :appid="appid" :initial-template-id="initialTemplateId" @committed="onCommitted" />
            </section>

            <section v-show="currentStep === 1" class="panel step-card">
              <h3 class="panel__title">步骤二 · 体验版验证</h3>
              <div class="workbench__experience">
                <ExperienceQrcode :key="appid" :appid="appid" />
                <div class="workbench__experience-text">
                  <p>
                    让协会的试用成员扫描体验版二维码验证：会员注册、活动报名、缴费等主要流程是否可用。
                  </p>
                  <p v-if="wxVersion">
                    <template v-if="wxVersion.exp">
                      微信侧当前体验版：{{ displayValue(wxVersion.exp.version, '未命名版本') }} · 上传于
                      {{ formatDateTime(wxVersion.exp.at) }}
                      <template v-if="wxVersion.exp.desc"> · {{ wxVersion.exp.desc }}</template>
                    </template>
                    <template v-else>微信侧还没有体验版，请先在「上传代码」步骤提交代码包。</template>
                  </p>
                  <ul>
                    <li>体验成员在「配置 → 体验成员」中添加微信号。</li>
                    <li>若页面访问异常，先检查「域名」配置中的 request 与 webview 域名。</li>
                    <li>验证通过后再进入下一步提交审核，可显著降低驳回率。</li>
                  </ul>
                  <el-button type="primary" size="small" @click="onStepClick(2)">验证完成，去提交审核</el-button>
                </div>
              </div>
            </section>

            <section v-show="currentStep === 2" class="panel step-card">
              <h3 class="panel__title">步骤三 · 提交审核</h3>
              <AuditSubmitPanel :appid="appid" @submitted="onAuditSubmitted" />
            </section>

            <section v-show="currentStep === 3" class="panel step-card">
              <h3 class="panel__title">步骤四 · 审核结果</h3>
              <AuditStatusPanel :appid="appid" :audit-id="auditId" @changed="loadAll" />
            </section>

            <section v-show="currentStep === 4" class="panel step-card">
              <h3 class="panel__title">步骤五 · 发布与灰度</h3>
              <PublishPanel :appid="appid" :status="release?.status" @changed="loadAll" />
            </section>

            <section v-show="currentStep === 5" class="panel step-card">
              <h3 class="panel__title">步骤六 · 线上版本</h3>
              <el-descriptions :column="2" border size="small">
                <el-descriptions-item label="线上版本号">
                  {{ displayValue(detail?.releaseVersion, '未发布') }}
                </el-descriptions-item>
                <el-descriptions-item label="发布单状态">
                  <StatusTag kind="releaseStatus" :value="release?.status" empty-text="—" />
                </el-descriptions-item>
                <el-descriptions-item label="微信侧线上版">
                  {{ wxVersion ? displayValue(wxVersion.release?.version, '微信侧未发布') : '未读到' }}
                </el-descriptions-item>
                <el-descriptions-item label="微信侧发布时间">
                  {{ formatDateTime(wxVersion?.release?.at) }}
                </el-descriptions-item>
                <el-descriptions-item label="版本描述">
                  {{ displayValue(release?.userDesc, '—') }}
                </el-descriptions-item>
                <el-descriptions-item label="更新时间">
                  {{ formatDateTime(release?.updatedAt) }}
                </el-descriptions-item>
              </el-descriptions>
              <el-alert
                v-if="versionDrift"
                class="mt-12"
                type="warning"
                show-icon
                :closable="false"
                :title="versionDrift"
              />
              <el-alert
                v-if="release?.status && String(release.status).toUpperCase() !== 'PUBLISHED'"
                class="mt-12"
                type="info"
                show-icon
                :closable="false"
                title="当前发布单尚未处于已上线状态"
                description="可在上一步完成发布，或使用右侧「版本回退」恢复到历史版本。"
              />
              <div class="mt-12">
                <el-button type="primary" plain size="small" @click="onApplyReleaseStep">
                  查看当前发布进度
                </el-button>
                <el-button size="small" text type="primary" @click="router.push('/logs')">
                  查看发布操作日志
                </el-button>
              </div>
            </section>
          </template>
        </div>

        <aside class="workbench__side">
          <section class="panel">
            <h3 class="panel__title">发布历史</h3>
            <ReleaseHistoryTab v-if="appid" :key="appid" :appid="appid" compact @changed="loadAll" />
            <EmptyState v-else compact description="选择小程序后显示发布历史" />
          </section>
        </aside>
      </div>
    </div>
  </div>
</template>

<style scoped>
.workbench__alert-text {
  margin: 0 0 8px;
  font-size: 12.5px;
  line-height: 1.6;
}

.mb-14 {
  margin-bottom: 14px;
}

.mt-12 {
  margin-top: 12px;
}

.workbench__layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 380px;
  gap: 14px;
  align-items: start;
}

@media (max-width: 1280px) {
  .workbench__layout {
    grid-template-columns: minmax(0, 1fr);
  }
}

.workbench__main {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}

.workbench__side {
  position: sticky;
  top: 12px;
}

.workbench__summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.workbench__summary-main {
  display: flex;
  align-items: center;
  gap: 18px;
  flex-wrap: wrap;
}

.workbench__summary-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}

.workbench__app {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 200px;
}

.workbench__kv {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 13px;
}

.workbench__steps {
  margin-top: 18px;
}

.workbench__step {
  cursor: pointer;
}

.workbench__step-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed var(--app-border-color);
  font-size: 13px;
}

.step-card :deep(.el-form-item) {
  margin-bottom: 14px;
}

.workbench__experience {
  display: flex;
  gap: 22px;
  flex-wrap: wrap;
  align-items: flex-start;
}

.workbench__experience-text {
  flex: 1;
  min-width: 260px;
  font-size: 13px;
  color: #4b5563;
  line-height: 1.8;
}

.workbench__experience-text p {
  margin: 0 0 8px;
}

.workbench__experience-text ul {
  margin: 0 0 12px;
  padding-left: 18px;
}
</style>
