<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Upload } from '@element-plus/icons-vue'

import {
  getPlatformDomainConfirmFile,
  getPlatformDomains,
  updatePlatformDomains,
  type PlatformDomainConfirmFile,
  type PlatformDomainKind,
  type PlatformDomains
} from '@/api/platform'
import { EmptyState } from '@/components'
import { describeError } from '@/utils/request'

type Action = 'set' | 'add' | 'delete'

const kindMetas: Array<{ value: PlatformDomainKind; label: string; hint: string }> = [
  { value: 'server', label: '服务器域名', hint: '授权小程序的 wx.request、uploadFile 等只能使用这里登记过的域名' },
  { value: 'jump', label: '业务域名（webview）', hint: '授权小程序内嵌 H5 的域名要先登记在这里，并把校验文件放到域名根目录' }
]

const kind = ref<PlatformDomainKind>('server')
const loading = ref(false)
const submitting = ref(false)
const errorText = ref('')
const data = ref<PlatformDomains | null>(null)
const working = ref<string[]>([])
const action = ref<Action>('set')
const newHost = ref('')
const modifyPublishedTogether = ref(false)
const confirmFile = ref<PlatformDomainConfirmFile | null>(null)

const activeHint = computed(() => kindMetas.find((item) => item.value === kind.value)?.hint ?? '')
const invalid = computed<string[]>(() => data.value?.invalid ?? [])
const published = computed<string[]>(() => data.value?.published ?? [])
const quotaText = computed(() => {
  if (!data.value) return ''
  return `上限 ${data.value.limit} 个，当前测试版 ${data.value.testing.length} 个；每月最多申请修改 50 次，建议一次改到位。`
})

/** 微信要的是裸主机名：带协议头、路径或端口都会被判成无效域名。 */
function normalizeHost(input: string): string {
  let value = input.trim()
  value = value.replace(/^https?:\/\//i, '')
  const slash = value.indexOf('/')
  if (slash >= 0) value = value.slice(0, slash)
  return value.replace(/\.+$/, '')
}

function addHost(): void {
  const raw = newHost.value.trim()
  const value = normalizeHost(raw)
  if (!value) {
    ElMessage.warning('请输入域名，例如 api.example.com')
    return
  }
  if (value.includes(':')) {
    ElMessage.warning('域名不能带端口：' + raw)
    return
  }
  if (working.value.includes(value)) {
    ElMessage.info('该域名已存在')
    return
  }
  working.value = [...working.value, value]
  newHost.value = ''
}

function removeHost(index: number): void {
  const list = [...working.value]
  list.splice(index, 1)
  working.value = list
}

/** 微信按 action 解释这份清单：delete 发要删的，add 发新增的，set 发完整的。 */
function payloadFor(
  current: Action,
  baseline: string[],
  edited: string[]
): { list: string[]; reason: string } {
  const removed = baseline.filter((item) => !edited.includes(item))
  const added = edited.filter((item) => !baseline.includes(item))
  if (!added.length && !removed.length) return { list: [], reason: '没有检测到变更，请先增删域名' }
  if (current === 'add' && removed.length) {
    return { list: [], reason: `有 ${removed.length} 个域名被移除，请改用「删除 delete」提交` }
  }
  if (current === 'delete' && added.length) {
    return { list: [], reason: `有 ${added.length} 个新域名不在清单里，请改用「新增 add」提交` }
  }
  if (current === 'set' && !edited.length) {
    return { list: [], reason: '清空全部域名请改用「删除 delete」，set 传空清单微信不接受' }
  }
  if (current === 'delete') return { list: removed, reason: '' }
  if (current === 'add') return { list: added, reason: '' }
  return { list: [...edited], reason: '' }
}

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    data.value = await getPlatformDomains(kind.value)
    working.value = [...(data.value?.testing ?? [])]
  } catch (err) {
    errorText.value = describeError(err)
    data.value = null
    working.value = []
  } finally {
    loading.value = false
  }
}

async function onSubmit(): Promise<void> {
  const { list, reason } = payloadFor(action.value, data.value?.testing ?? [], working.value)
  if (reason) {
    ElMessage.warning(reason)
    return
  }
  submitting.value = true
  errorText.value = ''
  try {
    data.value = await updatePlatformDomains(kind.value, {
      action: action.value,
      domains: list,
      modifyPublishedTogether: modifyPublishedTogether.value
    })
    working.value = [...(data.value?.testing ?? [])]
    ElMessage.success('白名单已更新，授权小程序的域名配置只能取其中的子集')
  } catch (err) {
    errorText.value = describeError(err)
  } finally {
    submitting.value = false
  }
}

async function loadConfirmFile(): Promise<void> {
  try {
    confirmFile.value = await getPlatformDomainConfirmFile()
  } catch (err) {
    confirmFile.value = null
    errorText.value = describeError(err)
  }
}

function onKindChange(): void {
  action.value = 'set'
  newHost.value = ''
  void load()
}

onMounted(() => {
  void load()
  void loadConfirmFile()
})
</script>

<template>
  <section class="panel" v-loading="loading">
    <h3 class="panel__title">
      <el-icon><Upload /></el-icon>
      <span>小程序域名白名单（第三方平台账号）</span>
      <el-button class="panel__more" text :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon>
        <span>重新加载</span>
      </el-button>
    </h3>

    <el-alert type="info" show-icon :closable="false" class="mb-12"
      title="授权给第三方代开发的小程序，其服务器域名与业务域名都只能取自这份白名单；先在这里登记，再去小程序详情页配置域名。" />

    <el-radio-group :model-value="kind" class="mb-12" @change="onKindChange()">
      <el-radio-button v-for="item in kindMetas" :key="item.value" :value="item.value">{{ item.label }}</el-radio-button>
    </el-radio-group>

    <p class="hint mb-12">{{ activeHint }}</p>
    <el-alert v-if="errorText" type="error" show-icon :closable="false" :title="errorText" class="mb-12" />

    <p class="label">可编辑清单（测试版）</p>
    <div class="tags">
      <el-tag
        v-for="(item, index) in working"
        :key="item"
        closable
        size="large"
        class="tag"
        @close="removeHost(index)"
      >
        <span class="mono">{{ item }}</span>
      </el-tag>
      <EmptyState v-if="!working.length && !loading" compact description="白名单为空" sub-description="未登记域名时，授权小程序的域名配置都会被微信拒绝。" />
    </div>

    <div class="editor">
      <el-input
        v-model="newHost"
        placeholder="例如 api.example.com（不带协议头与端口）"
        clearable
        style="max-width: 320px"
        @keyup.enter="addHost"
      >
        <template #append>
          <el-button @click="addHost">添加</el-button>
        </template>
      </el-input>

      <el-radio-group v-model="action">
        <el-radio-button value="set">全量覆盖 set</el-radio-button>
        <el-radio-button value="add">新增 add</el-radio-button>
        <el-radio-button value="delete">删除 delete</el-radio-button>
      </el-radio-group>

      <span class="editor__field">
        <el-checkbox v-model="modifyPublishedTogether">同时修改全网发布版</el-checkbox>
        <span class="hint hint--inline">
          {{ modifyPublishedTogether ? '线上小程序会立刻跟着变，删除前请确认没有已发布的小程序在用。' : '只改测试版；线上要立即用上新域名时再勾选。' }}
        </span>
      </span>

      <el-button type="primary" :loading="submitting" @click="onSubmit">提交修改</el-button>
    </div>

    <p class="hint">{{ quotaText }}</p>

    <div class="reads">
      <div>
        <p class="label">全网发布版</p>
        <div class="tags">
          <el-tag v-for="item in published" :key="item" size="small" effect="plain"><span class="mono">{{ item }}</span></el-tag>
          <span v-if="!published.length" class="hint">未发布或微信未返回</span>
        </div>
      </div>
      <div>
        <p class="label">未通过验证</p>
        <div class="tags">
          <el-tag v-for="item in invalid" :key="item" size="small" type="danger" effect="plain"><span class="mono">{{ item }}</span></el-tag>
          <span v-if="!invalid.length" class="hint">无</span>
        </div>
      </div>
    </div>

    <div v-if="confirmFile?.fileContent" class="verify">
      <span class="label">业务域名校验文件：</span>
      <span class="mono strong">{{ confirmFile.fileName }}</span>
      <p class="hint">放到域名服务器根目录（例如 https://h5.example.org/{{ confirmFile.fileName }}）后，微信才会接受该业务域名。</p>
    </div>
  </section>
</template>

<style scoped>
.mb-12 {
  margin-bottom: 12px;
}

.label {
  margin: 12px 0 6px;
  font-size: 13px;
  color: var(--app-text-muted);
}

.hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--app-text-muted);
}

.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  min-height: 32px;
}

.tag {
  max-width: 100%;
}

.editor {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}

.editor__field {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.hint--inline {
  margin: 0;
}

.reads {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 14px;
  margin-top: 14px;
}

.verify {
  margin-top: 14px;
}

.strong {
  font-weight: 600;
}

.mono {
  font-family: var(--app-font-mono, ui-monospace, monospace);
}
</style>
