<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CopyDocument, Link, Refresh, Right } from '@element-plus/icons-vue'

import {
  createAuthSession,
  getAuthSession,
  type AuthSessionCreated,
  type AuthSessionResult,
  type AuthSessionStatus
} from '@/api/authorizer'
import { EmptyState, PageHeader, QrCodeCanvas, StatusTag } from '@/components'
import { copyToClipboard, formatDateTime } from '@/utils/format'
import { describeError } from '@/utils/request'

const POLL_INTERVAL_MS = 2500

const router = useRouter()
const route = useRoute()

/** 带 appid 进来时链接锁定到这一个小程序，微信侧会隐藏其他账号 */
const lockedAppid = ref(typeof route.query.appid === 'string' ? route.query.appid : '')

/** 微信文档里本站会用到的权限集；列表外的 id 可以手输 */
const SCOPE_OPTIONS = [
  { id: 18, label: '18 · 小程序代码提交 / 审核 / 发布、服务器与业务域名' },
  { id: 30, label: '30 · 小程序名称 / 头像 / 简介等基本信息' },
  { id: 3, label: '3 · 基本信息读取与名称检测' },
  { id: 1, label: '1 · 小程序开发与管理' },
  { id: 4, label: '4 · 小程序基本信息设置' },
  { id: 17, label: '17 · 小程序类目管理' }
]

const funcScopeIds = ref<Array<number | string>>([])
const scopeMissingRelease = computed(
  () => funcScopeIds.value.length > 0 && !funcScopeIds.value.map((id) => String(id)).includes('18')
)

const creating = ref(false)
const polling = ref(false)
const session = ref<AuthSessionCreated | null>(null)
const result = ref<AuthSessionResult | null>(null)
const errorMessage = ref('')

let timer: number | null = null

const status = computed<AuthSessionStatus | ''>(() => result.value?.status ?? '')
const finished = computed(() => status.value === 'CONFIRMED' || status.value === 'EXPIRED' || status.value === 'FAILED')
const expiresAtText = computed(() => formatDateTime(session.value?.expiresAt ?? null, '未提供有效期'))
const resultAppid = computed(() => result.value?.resultAppid ?? '')

function stopPolling(): void {
  if (timer !== null) {
    window.clearInterval(timer)
    timer = null
  }
  polling.value = false
}

function startPolling(state: string): void {
  stopPolling()
  polling.value = true
  timer = window.setInterval(async () => {
    try {
      const next = await getAuthSession(state)
      result.value = next
      if (next.status === 'CONFIRMED') {
        stopPolling()
        ElMessage.success(`授权成功：${next.resultAppid ?? ''}`)
        window.setTimeout(() => {
          if (next.resultAppid) {
            void router.push(`/authorizers/${next.resultAppid}`)
          } else {
            void router.push('/authorizers')
          }
        }, 800)
      } else if (next.status === 'EXPIRED' || next.status === 'FAILED') {
        stopPolling()
        ElMessage.warning(next.errorMessage || (next.status === 'EXPIRED' ? '授权链接已过期，请重新生成' : '授权失败'))
      }
    } catch (error) {
      stopPolling()
      errorMessage.value = describeError(error)
    }
  }, POLL_INTERVAL_MS)
}

async function onCreate(): Promise<void> {
  creating.value = true
  errorMessage.value = ''
  result.value = null
  stopPolling()
  try {
    const created = await createAuthSession({ bizAppid: lockedAppid.value, funcScopeIds: funcScopeIds.value })
    session.value = created
    result.value = { state: created.state, status: 'WAITING', resultAppid: null, errorMessage: null }
    startPolling(created.state)
  } catch (error) {
    session.value = null
    errorMessage.value = describeError(error)
  } finally {
    creating.value = false
  }
}

function onSwitchGeneral(): void {
  lockedAppid.value = ''
  void onCreate()
}

async function onCopyUrl(): Promise<void> {
  const url = session.value?.authUrl ?? ''
  if (!url) return
  const ok = await copyToClipboard(url)
  ElMessage[ok ? 'success' : 'error'](ok ? '授权链接已复制' : '复制失败，请手动选择链接')
}

function onOpenUrl(): void {
  const url = session.value?.authUrl
  if (!url) return
  window.open(url, '_blank', 'noopener')
}

onBeforeUnmount(stopPolling)
onMounted(() => {
  if (lockedAppid.value) void onCreate()
})
</script>

<template>
  <div class="page">
    <div class="page__inner">
      <PageHeader
        title="扫码授权接入小程序"
        :subtitle="
          lockedAppid
            ? `为 AppID ${lockedAppid} 生成专属授权链接，管理员扫码确认本页会自动轮询`
            : '生成第三方平台授权链接，由目标小程序的管理员微信扫码并确认授权，页面会自动轮询授权结果'
        "
      >
        <template #extra>
          <el-button :loading="creating" type="primary" @click="onCreate">
            <el-icon><Refresh /></el-icon>
            <span>{{ session ? '重新生成链接' : '生成授权链接' }}</span>
          </el-button>
          <el-button text @click="router.push('/authorizers')">返回列表</el-button>
        </template>
      </PageHeader>

      <el-alert
        v-if="lockedAppid"
        type="info"
        show-icon
        :closable="false"
        :title="`已锁定 AppID：${lockedAppid}`"
        class="apply__alert"
      >
        <p class="apply__alert-text">
          微信授权页只会列出这个小程序，且只有它的管理员能扫码，其他人扫码会报错；链接里的 biz_appid
          由后端拼好，权限集由管理员在授权页勾选。
        </p>
        <el-button size="small" plain @click="onSwitchGeneral">不锁定，生成通用授权链接</el-button>
      </el-alert>

      <el-alert
        v-if="errorMessage"
        type="error"
        show-icon
        :closable="false"
        :title="errorMessage"
        description="若提示接口不存在，请确认后端已实现 POST /api/authorizers/auth-sessions。"
        class="apply__alert"
      />

      <div class="grid-two-col">
        <section class="panel apply-qr">
          <h2 class="panel__title">
            <el-icon><Link /></el-icon>
            <span>授权二维码</span>
          </h2>

          <div class="apply-scope">
            <span class="apply-scope__label">授权权限集（可选，改完自动重新生成链接）</span>
            <el-select
              v-model="funcScopeIds"
              multiple
              filterable
              clearable
              allow-create
              default-first-option
              placeholder="留空＝已发布的全部权限集"
              @change="onCreate()"
            >
              <el-option v-for="opt in SCOPE_OPTIONS" :key="opt.id" :label="opt.label" :value="opt.id" />
            </el-select>
            <p class="apply-scope__hint">
              留空时链接不带 <span class="mono">category_id_list</span>，管理员可勾选本站已全网发布的全部权限集（推荐）；
              一旦指定，管理员就只能授权所选这几项。
            </p>
            <p v-if="scopeMissingRelease" class="apply-scope__warn">
              所选权限集里没有 18，上传代码、提交审核与发布都会报 48001。
            </p>
          </div>

          <div v-if="session" class="apply-qr__body">
            <QrCodeCanvas :text="session.mobileAuthUrl ?? ''" :size="236" empty-text="未生成 H5 授权链接">
              <p class="apply-qr__hint">
                二维码里是 H5 版授权链接：管理员用微信「扫一扫」，在手机微信内直接选择账号完成授权。
              </p>
              <p class="apply-qr__hint">
                想在电脑上授权，就点「打开授权链接」访问 PC 版页面，再扫该页面上下发的微信二维码。
              </p>
            </QrCodeCanvas>

            <div class="apply-qr__meta">
              <div class="flex-row">
                <span class="muted">当前状态</span>
                <StatusTag kind="authSession" :value="status || 'WAITING'" />
                <span v-if="polling" class="muted">每 {{ POLL_INTERVAL_MS / 1000 }} 秒查询一次</span>
              </div>
              <div class="flex-row">
                <span class="muted">state</span>
                <span class="mono">{{ session.state }}</span>
              </div>
              <div class="flex-row">
                <span class="muted">有效期至</span>
                <span>{{ expiresAtText }}</span>
              </div>
              <div class="apply-qr__actions">
                <el-button :icon="Link" @click="onOpenUrl">打开授权链接</el-button>
                <el-button :icon="CopyDocument" @click="onCopyUrl">复制链接</el-button>
              </div>
              <el-input
                :model-value="session.authUrl"
                type="textarea"
                :rows="3"
                readonly
                resize="none"
                class="mono"
              />
              <p v-if="resultAppid" class="apply-qr__success">
                授权完成，AppID：<span class="mono strong">{{ resultAppid }}</span>
              </p>
            </div>
          </div>

          <EmptyState
            v-else
            description="尚未生成授权链接"
            sub-description="点击右上角「生成授权链接」，调用 POST /api/authorizers/auth-sessions 获取第三方平台授权 URL。"
          >
            <el-button type="primary" :loading="creating" @click="onCreate">生成授权链接</el-button>
          </EmptyState>
        </section>

        <div class="flex-col">
          <section class="panel">
            <h2 class="panel__title">接入流程</h2>
            <el-steps direction="vertical" :active="finished ? 3 : session ? 2 : 1" class="apply-steps">
              <el-step title="生成授权链接" description="平台侧调用接口，得到带 state 的第三方平台授权 URL" status="success" />
              <el-step
                title="管理员扫码"
                description="小程序管理员用微信扫码，选择要授权给第三方平台的小程序"
                :status="session && !finished ? 'process' : undefined"
              />
              <el-step
                title="确认授权"
                description="管理员勾选授权权限集并确认，微信回调平台推送授权事件"
                :status="status === 'CONFIRMED' ? 'success' : undefined"
              />
              <el-step
                title="进入发布工作台"
                description="授权完成后自动刷新令牌，即可上传代码、提交审核与发布"
                :status="status === 'CONFIRMED' ? 'success' : undefined"
              />
            </el-steps>
          </section>

          <section class="panel">
            <h2 class="panel__title">常见问题</h2>
            <ul class="apply-faq">
              <li>扫码后提示「授权已过期」：链接有效期由后端 state 决定，重新生成即可。</li>
              <li>小程序必须已完成微信认证，否则无法授权给第三方平台。</li>
              <li>只授权「开发管理」权限集即可完成发布流程，其余权限按需勾选。</li>
              <li>轮询依赖 GET /api/authorizers/auth-sessions/{state}，若长时间 WAITING 请检查回调域名配置。</li>
            </ul>
            <el-button v-if="status === 'CONFIRMED' && resultAppid" type="primary" @click="router.push(`/authorizers/${resultAppid}`)">
              <el-icon><Right /></el-icon>
              <span>查看该小程序详情</span>
            </el-button>
          </section>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.apply__alert {
  margin-bottom: 14px;
}

.apply__alert-text {
  margin: 0 0 8px;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--app-text-muted);
}

.apply-scope {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 16px;
}

.apply-scope__label {
  font-size: 12px;
  color: var(--app-text-muted);
}

.apply-scope__hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-text-muted);
}

.apply-scope__warn {
  margin: 0;
  font-size: 12px;
  color: var(--el-color-warning);
}

.apply-qr__body {
  display: flex;
  gap: 22px;
  flex-wrap: wrap;
}

.apply-qr__hint {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--app-text-muted);
  text-align: center;
  line-height: 1.5;
}

.apply-qr__meta {
  flex: 1;
  min-width: 260px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.apply-qr__actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.apply-qr__success {
  margin: 0;
  color: var(--el-color-success);
  font-size: 13px;
}

.apply-steps {
  padding: 6px 0 0;
}

.apply-faq {
  margin: 0;
  padding-left: 18px;
  color: #4b5563;
  font-size: 13px;
  line-height: 1.9;
}
</style>
