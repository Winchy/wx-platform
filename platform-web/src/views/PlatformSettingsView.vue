<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Check, Connection, CopyDocument, Refresh, Tickets } from '@element-plus/icons-vue'

import {
  checkPlatformConnectivity,
  getPlatformConfig,
  listVerifyTickets,
  restartTicketPush,
  updatePlatformConfig,
  type ConnectivityCheckResult,
  type PlatformConfig,
  type PlatformConfigUpdateParams,
  type TicketPushRestartResult,
  type VerifyTicket
} from '@/api/platform'
import { EmptyState, PageHeader } from '@/components'
import PlatformDomainPanel from '@/views/config/PlatformDomainPanel.vue'
import { copyToClipboard, displayValue, formatDateTime, formatRelativeTime } from '@/utils/format'
import { describeError } from '@/utils/request'

const loading = ref(false)
const saving = ref(false)
const checking = ref(false)
const loadError = ref('')
const saveError = ref('')
const checkError = ref('')
const config = ref<PlatformConfig | null>(null)
const checkResult = ref<ConnectivityCheckResult | null>(null)

const tickets = ref<VerifyTicket[]>([])
const ticketsLoading = ref(false)
const ticketsError = ref('')
const ticketPushing = ref(false)
const ticketPushResult = ref<TicketPushRestartResult | null>(null)

/** 密钥类字段只写入不回显，留空表示保持原值 */
const form = reactive({
  componentAppid: '',
  componentSecret: '',
  messageToken: '',
  encodingAesKey: '',
  siteBase: '',
  apiBase: '',
  status: 1
})

const statusOptions = [
  { value: 1, label: '已启用' },
  { value: 0, label: '未启用' }
]

const enabled = computed(() => Number(config.value?.status) === 1)

const statusText = computed(() => {
  const raw = config.value?.status
  if (raw === null || raw === undefined) return '未设置'
  return statusOptions.find((item) => item.value === Number(raw))?.label ?? String(raw)
})

const ticketPushText = computed(() => {
  const result = ticketPushResult.value
  if (!result) return ''
  const ok = Number(result.errcode) === 0
  return `${ok ? '已通知微信' : '请求失败'}：errcode=${displayValue(result.errcode, '—')}，${displayValue(result.errmsg, '')}；当前票据距今 ${displayValue(result.ticketAgeSeconds, '—')} 秒`
})

const checkText = computed(() => {
  const result = checkResult.value
  if (!result) return ''
  if (result.ok) {
    return `连通正常：component_access_token 获取成功，有效期 ${displayValue(result.expiresIn, '未返回')} 秒`
  }
  return `连通失败：errcode=${displayValue(result.errcode, '—')}，${displayValue(result.errmsg, '未返回错误信息')}`
})

/** el-table 插槽只提供松类型的 row，这里用下标回到强类型数组 */
function rowAt(index: number): VerifyTicket {
  return tickets.value[index]
}

function fillForm(source: PlatformConfig | null): void {
  form.componentAppid = source?.componentAppid ?? ''
  form.siteBase = source?.siteBase ?? ''
  form.apiBase = source?.apiBase ?? ''
  form.status = Number(source?.status ?? 0)
  form.componentSecret = ''
  form.messageToken = ''
  form.encodingAesKey = ''
}

async function loadConfig(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    config.value = await getPlatformConfig()
    fillForm(config.value)
  } catch (err) {
    loadError.value = describeError(err)
  } finally {
    loading.value = false
  }
}

async function loadTickets(): Promise<void> {
  ticketsLoading.value = true
  ticketsError.value = ''
  try {
    const data = await listVerifyTickets()
    tickets.value = Array.isArray(data) ? data : []
  } catch (err) {
    ticketsError.value = describeError(err)
    tickets.value = []
  } finally {
    ticketsLoading.value = false
  }
}

async function onSave(): Promise<void> {
  if (!form.componentAppid.trim()) {
    ElMessage.warning('开放平台 AppID 不能为空')
    return
  }
  const aesKey = form.encodingAesKey.trim()
  if (aesKey && aesKey.length !== 43) {
    ElMessage.error('EncodingAESKey 必须是 43 位字符')
    return
  }
  const payload: PlatformConfigUpdateParams = {
    componentAppid: form.componentAppid.trim(),
    siteBase: form.siteBase.trim(),
    apiBase: form.apiBase.trim(),
    status: Number(form.status) === 1 ? 1 : 0,
    componentSecret: form.componentSecret.trim() || undefined,
    messageToken: form.messageToken.trim() || undefined,
    encodingAesKey: aesKey || undefined
  }
  saving.value = true
  saveError.value = ''
  try {
    config.value = await updatePlatformConfig(payload)
    fillForm(config.value)
    ElMessage.success('平台配置已保存')
  } catch (err) {
    saveError.value = describeError(err)
  } finally {
    saving.value = false
  }
}

async function onRestartTicketPush(): Promise<void> {
  ticketPushing.value = true
  ticketsError.value = ''
  try {
    ticketPushResult.value = await restartTicketPush()
    ElMessage.success('已请求微信重新开始推送 ticket')
    await Promise.all([loadConfig(), loadTickets()])
  } catch (err) {
    ticketsError.value = describeError(err)
  } finally {
    ticketPushing.value = false
  }
}

async function onCheck(): Promise<void> {
  checking.value = true
  checkError.value = ''
  checkResult.value = null
  try {
    checkResult.value = await checkPlatformConnectivity()
    ElMessage[checkResult.value.ok ? 'success' : 'warning'](checkText.value || '校验完成')
    await loadConfig()
  } catch (err) {
    checkError.value = describeError(err)
    checkResult.value = { ok: false, errcode: null, errmsg: checkError.value, expiresIn: null }
  } finally {
    checking.value = false
  }
}

async function copyText(value: string | null | undefined, label: string): Promise<void> {
  if (!value) return
  const ok = await copyToClipboard(value)
  ElMessage[ok ? 'success' : 'error'](ok ? `${label}已复制` : '复制失败')
}

onMounted(() => {
  void loadConfig()
  void loadTickets()
})
</script>

<template>
  <div class="page">
    <div class="page__inner">
      <PageHeader title="平台配置" subtitle="微信开放平台第三方平台的凭证与回调信息，密钥仅写入不回显">
        <template #extra>
          <el-button :loading="loading" @click="loadConfig">
            <el-icon><Refresh /></el-icon>
            <span>重新加载</span>
          </el-button>
        </template>
      </PageHeader>

      <el-alert v-if="loadError" type="error" show-icon :closable="false" :title="loadError" class="mb-14" />

      <div class="grid-two-col">
        <section class="panel" v-loading="loading">
          <h3 class="panel__title">
            <el-icon><Connection /></el-icon>
            <span>凭证配置</span>
          </h3>

          <el-form label-width="170px" label-position="right" @submit.prevent="onSave">
            <el-form-item label="开放平台 AppID">
              <el-input v-model="form.componentAppid" placeholder="第三方平台 AppID（wx 开头）" clearable />
            </el-form-item>

            <el-form-item label="AppSecret">
              <el-input
                v-model="form.componentSecret"
                type="password"
                show-password
                placeholder="留空表示不修改"
                autocomplete="new-password"
              />
              <span class="settings__hint">
                当前状态：
                <el-tag :type="config?.secretSet ? 'success' : 'info'" size="small" effect="plain">
                  {{ config?.secretSet ? '已设置' : '未设置' }}
                </el-tag>
              </span>
            </el-form-item>

            <el-form-item label="消息校验 Token">
              <el-input
                v-model="form.messageToken"
                type="password"
                show-password
                placeholder="留空表示不修改"
                autocomplete="new-password"
              />
              <span class="settings__hint">
                当前状态：
                <el-tag :type="config?.messageTokenSet ? 'success' : 'info'" size="small" effect="plain">
                  {{ config?.messageTokenSet ? '已设置' : '未设置' }}
                </el-tag>
              </span>
            </el-form-item>

            <el-form-item label="EncodingAESKey">
              <el-input
                v-model="form.encodingAesKey"
                type="password"
                show-password
                placeholder="留空表示不修改（43 位）"
                autocomplete="new-password"
              />
              <span class="settings__hint">
                当前状态：
                <el-tag :type="config?.aesKeySet ? 'success' : 'info'" size="small" effect="plain">
                  {{ config?.aesKeySet ? '已设置' : '未设置' }}
                </el-tag>
              </span>
            </el-form-item>

            <el-form-item label="站点基地址">
              <el-input v-model="form.siteBase" placeholder="例如 https://wx-platform.example.org" clearable />
              <span class="settings__hint">用于生成授权回调与消息校验 URL</span>
            </el-form-item>

            <el-form-item label="微信接口域名">
              <el-input v-model="form.apiBase" placeholder="留空则使用环境变量 WECHAT_API_BASE" clearable class="mono" />
              <span class="settings__hint">调用微信开放平台接口的基地址，仅联调/代理时填写；留空回落到 WECHAT_API_BASE（默认 https://api.weixin.qq.com）。</span>
            </el-form-item>

            <el-form-item label="平台启用状态">
              <el-select v-model="form.status" style="width: 200px">
                <el-option v-for="item in statusOptions" :key="item.value" :value="item.value" :label="item.label" />
              </el-select>
              <el-tag class="settings__tag" :type="enabled ? 'success' : 'info'" effect="plain" size="small">
                当前：{{ statusText }}
              </el-tag>
            </el-form-item>

            <el-form-item>
              <el-button type="primary" :loading="saving" @click="onSave">保存配置</el-button>
              <el-button :loading="checking" @click="onCheck">校验连通性</el-button>
              <span class="settings__hint">校验会真实调用一次 component_access_token 接口</span>
            </el-form-item>
          </el-form>

          <el-alert v-if="saveError" type="error" show-icon :closable="false" :title="`保存失败：${saveError}`" />

          <el-alert
            v-if="checkResult"
            :type="checkResult.ok ? 'success' : 'error'"
            show-icon
            :closable="false"
            :title="checkText"
          />
        </section>

        <div class="flex-col">
          <section class="panel">
            <h3 class="panel__title">
              <el-icon><Check /></el-icon>
              <span>回调地址（只读）</span>
            </h3>

            <div class="settings__callback">
              <span class="muted">授权事件接收 URL</span>
              <div class="settings__callback-row">
                <el-input :model-value="displayValue(config?.callbackUrl, '后端未返回 callbackUrl')" readonly class="mono" />
                <el-button :icon="CopyDocument" text @click="copyText(config?.callbackUrl, '回调地址')">复制</el-button>
              </div>
              <p class="settings__hint">
                请把它填到开放平台「授权事件接收 URL」，用于接收票据与授权/解除授权事件；第三方平台固定为 XML + 安全模式。
              </p>

              <span class="muted">消息与事件接收 URL</span>
              <div class="settings__callback-row">
                <el-input :model-value="displayValue(config?.messageUrl, '后端未返回 messageUrl')" readonly class="mono" />
                <el-button :icon="CopyDocument" text @click="copyText(config?.messageUrl, '消息接收地址')">复制</el-button>
              </div>
              <p class="settings__hint">
                请把它填到开放平台「消息与事件接收 URL」，结尾的 $APPID$ 原样保留，微信会替换成实际小程序的 appid。
              </p>
            </div>

            <el-alert
              v-if="config?.ephemeralMasterKey"
              class="settings__warn"
              type="warning"
              show-icon
              :closable="false"
              title="后端正在使用临时主密钥加密凭据"
              description="当前未配置稳定的加密主密钥，服务重启后 AppSecret、EncodingAESKey 将无法解密，需要重新填写。"
            />

            <el-descriptions :column="1" border size="small" class="settings__desc">
              <el-descriptions-item label="票据最近推送">
                {{ formatDateTime(config?.ticketReceivedAt) }}
                <span class="muted">（{{ formatRelativeTime(config?.ticketReceivedAt) }}）</span>
              </el-descriptions-item>
              <el-descriptions-item label="令牌过期时间">
                {{ formatDateTime(config?.tokenExpiresAt) }}
                <span class="muted">（{{ formatRelativeTime(config?.tokenExpiresAt) }}）</span>
              </el-descriptions-item>
            </el-descriptions>
          </section>

          <section class="panel">
            <h3 class="panel__title">
              <el-icon><Tickets /></el-icon>
              <span>票据推送历史</span>
              <el-button class="panel__more" text :loading="ticketsLoading" @click="loadTickets">刷新</el-button>
              <el-button text type="primary" :loading="ticketPushing" @click="onRestartTicketPush">
                重新推送票据
              </el-button>
            </h3>

            <el-alert v-if="ticketsError" type="error" show-icon :closable="false" :title="ticketsError" class="mb-12" />

            <el-alert
              v-if="ticketPushText"
              :type="Number(ticketPushResult?.errcode) === 0 ? 'success' : 'warning'"
              show-icon
              :closable="false"
              :title="ticketPushText"
              class="mb-12"
            />

            <el-table
              v-loading="ticketsLoading"
              :data="tickets"
              size="small"
              border
              max-height="280"
              empty-text="暂无票据记录"
            >
              <el-table-column label="ticket（已打码）" min-width="200">
                <template #default="{ $index }: { $index: number }">
                  <span class="mono">{{ displayValue(rowAt($index).ticket, '—') }}</span>
                </template>
              </el-table-column>
              <el-table-column label="接收时间" width="190">
                <template #default="{ $index }: { $index: number }">
                  {{ formatDateTime(rowAt($index).receivedAt) }}
                </template>
              </el-table-column>
            </el-table>

            <EmptyState
              v-if="!tickets.length && !ticketsLoading && !ticketsError"
              compact
              description="10 分钟内没有票据推送"
              sub-description="微信每 10 分钟推送一次 verify_ticket，若长期为空请检查回调 URL 与站点外网可达性。"
            />
          </section>
        </div>
      </div>

      <PlatformDomainPanel />
    </div>
  </div>
</template>

<style scoped>
.mb-14 {
  margin-bottom: 14px;
}

.mb-12 {
  margin-bottom: 12px;
}

.settings__hint {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: var(--app-text-muted);
  line-height: 1.6;
}

.settings__tag {
  margin-left: 10px;
}

.settings__callback-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.settings__callback {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
}

.settings__warn {
  margin-top: 12px;
}

.settings__desc {
  margin-top: 16px;
}
</style>
