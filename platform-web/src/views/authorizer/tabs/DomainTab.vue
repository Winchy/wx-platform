<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Check, Close, Plus, QuestionFilled, Refresh, Upload } from '@element-plus/icons-vue'

import {
  getServerDomains,
  getWebviewConfirmFile,
  getWebviewDomains,
  updateServerDomains,
  updateWebviewDomains,
  type DomainAction,
  type DomainConfig,
  type DomainType,
  type WebviewConfirmFile
} from '@/api/config'
import { getPlatformDomains, type PlatformDomainKind, type PlatformDomains } from '@/api/platform'
import { EmptyState, JsonBlock } from '@/components'
import { copyToClipboard, displayValue } from '@/utils/format'
import { describeError } from '@/utils/request'

interface DomainMeta {
  /** 后端驼峰字段名，同时作为提交时的 type */
  type: DomainType
  label: string
  hint: string
}

const props = defineProps<{ appid: string }>()

const domainMetas: DomainMeta[] = [
  { type: 'request', label: 'request 合法域名', hint: 'wx.request 使用的 HTTPS 接口域名' },
  { type: 'wsRequest', label: 'socket 合法域名', hint: 'wx.connectSocket 使用的 WSS 域名' },
  { type: 'uploadFile', label: 'uploadFile 域名', hint: 'wx.uploadFile 使用的域名' },
  { type: 'downloadFile', label: 'downloadFile 域名', hint: 'wx.downloadFile 使用的域名' },
  { type: 'udp', label: 'udp 域名', hint: 'wx.createUDPSocket 使用的域名' },
  { type: 'tcp', label: 'tcp 域名', hint: 'wx.createTCPSocket 使用的域名' }
]

const loading = ref(false)
const errorText = ref('')
const domains = ref<DomainConfig | null>(null)
const activeType = ref<DomainType>('request')
const action = ref<DomainAction>('set')
const newDomain = ref('')
const submitting = ref(false)

const working = reactive<Record<string, string[]>>({})

const webviewLoading = ref(false)
const webviewError = ref('')
const webview = ref<string[]>([])
const webviewWorking = ref<string[]>([])
const webviewAction = ref<DomainAction>('set')
const webviewNewDomain = ref('')
const webviewSubmitting = ref(false)

const confirmFile = ref<WebviewConfirmFile | null>(null)
const confirmFileError = ref('')

const serverAllow = ref<PlatformDomains | null>(null)
const jumpAllow = ref<PlatformDomains | null>(null)

/** 白名单里是裸主机名，小程序域名带协议（socket 还带端口），比较时统一成小写主机名。 */
function hostOf(entry: string): string {
  return entry
    .replace(/^[a-z][a-z0-9+.-]*:\/\//i, '')
    .replace(/\/.*$/, '')
    .split(':')[0]
    .toLowerCase()
}

function allowedHosts(kind: PlatformDomainKind): Set<string> | null {
  const source = kind === 'server' ? serverAllow.value : jumpAllow.value
  if (!source) return null
  return new Set([...(source.published ?? []), ...(source.testing ?? [])].map(hostOf))
}

/** 只有登记类 action 才需要比对白名单；delete 发的是已存在的域名，拦它等于拦清理。 */
function outsideAllowlist(list: string[], kind: PlatformDomainKind, current: DomainAction): string[] {
  if (current === 'delete') return []
  const allowed = allowedHosts(kind)
  if (!allowed) return []
  return list.filter((item) => !allowed.has(hostOf(item)))
}

const allowlistEmpty = computed(() => {
  const server = serverAllow.value
  return !!server && !server.published.length && !server.testing.length
})
const jumpAllowlistEmpty = computed(() => {
  const jump = jumpAllow.value
  return !!jump && !jump.published.length && !jump.testing.length
})

async function loadAllowlist(): Promise<void> {
  const [server, jump] = await Promise.allSettled([getPlatformDomains('server'), getPlatformDomains('jump')])
  serverAllow.value = server.status === 'fulfilled' ? server.value : null
  jumpAllow.value = jump.status === 'fulfilled' ? jump.value : null
}

const activeMeta = computed(() => domainMetas.find((item) => item.type === activeType.value) ?? domainMetas[0])
const activeList = computed<string[]>(() => working[activeMeta.value.type] ?? [])
const monthlyQuotaHint =
  '每月可修改 50 次（次月 1 日恢复），当月已用修改次数越多越容易失败，建议一次改到位。'

/** invalid 里的域名按驼峰类型给出，另外可能有未备案域名 */
const invalidEntries = computed<Array<{ label: string; items: string[] }>>(() => {
  const invalid = domains.value?.invalid ?? {}
  const entries: Array<{ label: string; items: string[] }> = []
  for (const meta of domainMetas) {
    const items = invalid[meta.type]
    if (items && items.length) entries.push({ label: meta.label, items })
  }
  const noIcp = invalid.noIcp
  if (noIcp && noIcp.length) entries.push({ label: '未备案域名', items: noIcp })
  return entries
})

function syncWorking(source: DomainConfig | null): void {
  for (const meta of domainMetas) {
    working[meta.type] = [...(source?.[meta.type] ?? [])]
  }
}

async function loadDomains(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    domains.value = await getServerDomains(props.appid)
    syncWorking(domains.value)
  } catch (err) {
    errorText.value = describeError(err)
    domains.value = null
    syncWorking(null)
  } finally {
    loading.value = false
  }
}

async function loadWebview(): Promise<void> {
  webviewLoading.value = true
  webviewError.value = ''
  try {
    webview.value = (await getWebviewDomains(props.appid)) ?? []
    webviewWorking.value = [...webview.value]
  } catch (err) {
    webviewError.value = describeError(err)
    webview.value = []
    webviewWorking.value = []
  } finally {
    webviewLoading.value = false
  }
}

async function loadConfirmFile(): Promise<void> {
  confirmFileError.value = ''
  try {
    confirmFile.value = await getWebviewConfirmFile(props.appid)
  } catch (err) {
    confirmFile.value = null
    confirmFileError.value = describeError(err)
  }
}

/** 微信要求带协议的完整域名：socket 用 wss，udp/tcp 用各自协议，其余 https。 */
const schemeByType: Record<DomainType, string> = {
  request: 'https://',
  wsRequest: 'wss://',
  uploadFile: 'https://',
  downloadFile: 'https://',
  udp: 'udp://',
  tcp: 'tcp://'
}

/** 输入允许裸域名或整段 URL，落到微信要的形态：协议 + 主机（可带端口），丢弃路径。 */
function normalizeDomain(input: string, scheme: string): string {
  const value = input.trim()
  if (!value) return ''
  const host = value.replace(/^[a-z][a-z0-9+.-]*:\/\//i, '').replace(/\/.*$/, '')
  return host ? scheme + host : ''
}

function addDomain(): void {
  const value = normalizeDomain(newDomain.value, schemeByType[activeMeta.value.type])
  if (!value) {
    ElMessage.warning('请输入域名，例如 api.example.com')
    return
  }
  const key = activeMeta.value.type
  const list = working[key] ?? []
  if (list.includes(value)) {
    ElMessage.info('该域名已存在')
    return
  }
  working[key] = [...list, value]
  newDomain.value = ''
}

function removeDomain(index: number): void {
  const key = activeMeta.value.type
  const list = [...(working[key] ?? [])]
  list.splice(index, 1)
  working[key] = list
}

function resetWorking(): void {
  syncWorking(domains.value)
  ElMessage.success('已还原为当前线上配置')
}

/** 微信按 action 解释列表：delete 发要删的域名，add 发新增的，set 发完整列表。 */
function payloadFor(
  current: DomainAction,
  baseline: string[],
  workingList: string[]
): { list: string[]; reason: string } {
  const removed = baseline.filter((item) => !workingList.includes(item))
  const added = workingList.filter((item) => !baseline.includes(item))
  if (!added.length && !removed.length) return { list: [], reason: '没有检测到变更，请先增删域名' }
  if (current === 'add' && removed.length) {
    return { list: [], reason: `有 ${removed.length} 个域名被移除，请改用「删除 delete」提交` }
  }
  if (current === 'delete' && added.length) {
    return { list: [], reason: `有 ${added.length} 个新域名不在清单里，请改用「新增 add」提交` }
  }
  if (current === 'set' && !workingList.length) {
    return { list: [], reason: '清空全部域名请改用「删除 delete」，set 传空列表微信不接受' }
  }
  if (current === 'delete') return { list: removed, reason: '' }
  if (current === 'add') return { list: added, reason: '' }
  return { list: [...workingList], reason: '' }
}

async function onSubmit(): Promise<void> {
  const meta = activeMeta.value
  const baseline = domains.value?.[meta.type] ?? []
  const { list, reason } = payloadFor(action.value, baseline, working[meta.type] ?? [])
  if (reason) {
    ElMessage.warning(reason)
    return
  }
  const outside = outsideAllowlist(list, 'server', action.value)
  if (outside.length) {
    ElMessage.warning(
      `微信只接受第三方平台账号白名单里的域名，这些还没登记：${outside.join('、')}。请先到「运维 → 平台配置 → 小程序域名白名单」添加。`
    )
    return
  }
  submitting.value = true
  try {
    const updated = await updateServerDomains(props.appid, {
      action: action.value,
      type: meta.type,
      domains: list
    })
    // 后端只回填被改动的类型 + invalid
    if (updated && Array.isArray(updated[meta.type])) {
      working[meta.type] = [...(updated[meta.type] as string[])]
      domains.value = domains.value ? { ...domains.value, [meta.type]: [...working[meta.type]] } : domains.value
    }
    if (updated?.invalid) {
      domains.value = domains.value ? { ...domains.value, invalid: updated.invalid } : domains.value
    }
    ElMessage.success(`${meta.label} 已提交修改`)
  } catch (err) {
    ElMessage.error(describeError(err))
  } finally {
    submitting.value = false
  }
}

function addWebviewDomain(): void {
  const value = normalizeDomain(webviewNewDomain.value, 'https://')
  if (!value) {
    ElMessage.warning('请输入业务域名')
    return
  }
  if (webviewWorking.value.includes(value)) {
    ElMessage.info('该域名已存在')
    return
  }
  webviewWorking.value = [...webviewWorking.value, value]
  webviewNewDomain.value = ''
}

function removeWebviewDomain(index: number): void {
  const list = [...webviewWorking.value]
  list.splice(index, 1)
  webviewWorking.value = list
}

async function onSubmitWebview(): Promise<void> {
  const { list, reason } = payloadFor(webviewAction.value, webview.value, webviewWorking.value)
  if (reason) {
    ElMessage.warning(reason)
    return
  }
  const outside = outsideAllowlist(list, 'jump', webviewAction.value)
  if (outside.length) {
    ElMessage.warning(
      `业务域名必须先在第三方平台账号登记，这些还没登记：${outside.join('、')}。请先到「运维 → 平台配置 → 小程序域名白名单」的业务域名里添加，并把校验文件放到域名根目录。`
    )
    return
  }
  webviewSubmitting.value = true
  try {
    const updated = await updateWebviewDomains(props.appid, {
      action: webviewAction.value,
      domains: list
    })
    webview.value = updated ?? []
    webviewWorking.value = [...webview.value]
    ElMessage.success('业务域名已提交修改')
  } catch (err) {
    ElMessage.error(describeError(err))
  } finally {
    webviewSubmitting.value = false
  }
}

async function copyVerifyFile(): Promise<void> {
  const content = confirmFile.value?.fileContent
  if (!content) return
  const ok = await copyToClipboard(content)
  ElMessage[ok ? 'success' : 'error'](ok ? '校验文件内容已复制' : '复制失败')
}

watch(() => props.appid, () => {
  void loadDomains()
  void loadWebview()
  void loadConfirmFile()
})

onMounted(() => {
  void loadDomains()
  void loadWebview()
  void loadConfirmFile()
  void loadAllowlist()
})

defineExpose({ reload: loadDomains })
</script>

<template>
  <div class="domain-tab">
    <section class="panel panel--flat" v-loading="loading">
      <h3 class="panel__title">
        <el-icon><Check /></el-icon>
        <span>服务器域名</span>
        <el-tooltip :content="monthlyQuotaHint" placement="top">
          <el-icon class="domain-tab__help"><QuestionFilled /></el-icon>
        </el-tooltip>
        <el-button class="panel__more" text :loading="loading" @click="loadDomains">
          <el-icon><Refresh /></el-icon>
          <span>重新加载</span>
        </el-button>
      </h3>

      <el-alert v-if="errorText" type="error" show-icon :closable="false" :title="errorText" class="mb-12" />

      <el-alert
        v-for="entry in invalidEntries"
        :key="entry.label"
        type="warning"
        show-icon
        :closable="false"
        :title="`${entry.label}：微信未接受 ${entry.items.join('、')}`"
        class="mb-12"
      />

      <el-tabs v-model="activeType" class="domain-tab__tabs">
        <el-tab-pane v-for="meta in domainMetas" :key="meta.type" :name="meta.type">
          <template #label>
            <span>{{ meta.label }}</span>
            <el-badge v-if="working[meta.type]?.length" :value="working[meta.type]?.length" type="info" class="domain-tab__badge" />
          </template>
        </el-tab-pane>
      </el-tabs>

      <el-alert
        v-if="allowlistEmpty"
        type="warning"
        show-icon
        :closable="false"
        title="第三方平台账号还没有登记任何服务器域名，此时任何提交都会被微信拒绝。请先到「运维 → 平台配置 → 小程序域名白名单」添加。"
        class="mb-12"
      />

      <p class="muted domain-tab__hint">
        {{ activeMeta?.hint }}（提交时自动补协议、去掉路径与结尾斜杠；域名需已在第三方平台账号的白名单里登记）
      </p>

      <div class="domain-tab__tags">
        <el-tag
          v-for="(item, index) in activeList"
          :key="item"
          closable
          size="large"
          class="domain-tab__tag"
          @close="removeDomain(index)"
        >
          <span class="mono">{{ item }}</span>
        </el-tag>
        <div v-if="!activeList.length" class="muted domain-tab__empty">
          <el-icon><Close /></el-icon>
          <span>该类型暂未配置域名</span>
        </div>
      </div>

      <div class="domain-tab__editor">
        <el-input
          v-model="newDomain"
          placeholder="例如 api.example.com 或 mp-proxy.example.com"
          clearable
          style="max-width: 340px"
          @keyup.enter="addDomain"
        >
          <template #append>
            <el-button :icon="Plus" @click="addDomain">添加</el-button>
          </template>
        </el-input>

        <el-radio-group v-model="action">
          <el-radio-button value="set">全量覆盖 set</el-radio-button>
          <el-radio-button value="add">新增 add</el-radio-button>
          <el-radio-button value="delete">删除 delete</el-radio-button>
        </el-radio-group>

        <el-button type="primary" :loading="submitting" @click="onSubmit">提交修改</el-button>
        <el-button text :disabled="submitting" @click="resetWorking">还原</el-button>
      </div>
    </section>

    <section class="panel panel--flat" v-loading="webviewLoading">
      <h3 class="panel__title">
        <el-icon><Upload /></el-icon>
        <span>业务域名（webview）</span>
        <el-button class="panel__more" text :loading="webviewLoading" @click="loadWebview">
          <el-icon><Refresh /></el-icon>
          <span>重新加载</span>
        </el-button>
      </h3>

      <el-alert v-if="webviewError" type="error" show-icon :closable="false" :title="webviewError" class="mb-12" />

      <el-alert
        v-if="jumpAllowlistEmpty"
        type="warning"
        show-icon
        :closable="false"
        title="第三方平台账号还没有登记任何业务域名，webview 域名提交都会被微信拒绝。请先到「运维 → 平台配置 → 小程序域名白名单」的业务域名里添加。"
        class="mb-12"
      />

      <div class="domain-tab__tags">
        <el-tag
          v-for="(item, index) in webviewWorking"
          :key="item"
          closable
          size="large"
          class="domain-tab__tag"
          @close="removeWebviewDomain(index)"
        >
          <span class="mono">{{ item }}</span>
        </el-tag>
        <EmptyState
          v-if="!webviewWorking.length && !webviewLoading"
          compact
          description="尚未配置 webview 业务域名"
          sub-description="小程序内嵌 H5（如协会章程、活动报名页）需要在此登记域名并放置校验文件。"
        />
      </div>

      <div class="domain-tab__editor">
        <el-input
          v-model="webviewNewDomain"
          placeholder="例如 h5.example.org"
          clearable
          style="max-width: 340px"
          @keyup.enter="addWebviewDomain"
        >
          <template #append>
            <el-button :icon="Plus" @click="addWebviewDomain">添加</el-button>
          </template>
        </el-input>

        <el-radio-group v-model="webviewAction">
          <el-radio-button value="set">全量覆盖 set</el-radio-button>
          <el-radio-button value="add">新增 add</el-radio-button>
          <el-radio-button value="delete">删除 delete</el-radio-button>
        </el-radio-group>

        <el-button type="primary" :loading="webviewSubmitting" @click="onSubmitWebview">提交修改</el-button>
      </div>

      <div class="domain-tab__verify">
        <span class="muted">校验文件：</span>
        <span class="mono strong">{{ displayValue(confirmFile?.fileName, '未提供') }}</span>
        <el-button v-if="confirmFile?.fileContent" text type="primary" size="small" @click="copyVerifyFile">
          复制文件内容
        </el-button>
        <el-button text size="small" :loading="webviewLoading" @click="loadConfirmFile">
          <el-icon><Refresh /></el-icon>
          <span>重新获取</span>
        </el-button>
        <p class="muted domain-tab__hint">
          需把该文件放到业务域名服务器根目录后，微信才会允许在 webview 中打开对应页面。
        </p>
        <el-alert v-if="confirmFileError" type="warning" show-icon :closable="false" :title="confirmFileError" class="mb-12" />
        <JsonBlock v-if="confirmFile?.fileContent" :value="confirmFile.fileContent" :format-json="false" :max-height="120" />
      </div>
    </section>
  </div>
</template>

<style scoped>
.panel--flat {
  padding: 14px 16px;
}

.panel--flat + .panel--flat {
  margin-top: 14px;
}

.mb-12 {
  margin-bottom: 12px;
}

.domain-tab__help {
  color: var(--app-text-muted);
}

.domain-tab__tabs {
  margin-bottom: 4px;
}

.domain-tab__badge :deep(.el-badge__content) {
  margin-left: 6px;
}

.domain-tab__hint {
  margin: 0 0 10px;
  font-size: 12px;
}

.domain-tab__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  min-height: 40px;
  margin-bottom: 12px;
}

.domain-tab__tag {
  max-width: 100%;
}

.domain-tab__empty {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}

.domain-tab__editor {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}

.domain-tab__verify {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--app-border-color);
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
</style>
