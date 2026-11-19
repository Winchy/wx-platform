<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { CaretRight, Delete, Plus, Refresh } from '@element-plus/icons-vue'

import { commitCode, listTemplates, syncTemplates, type CodeTemplate, type ReleaseRecord } from '@/api/release'
import { describeError, describeErrorDetail } from '@/utils/request'
import { useRouter } from 'vue-router'

const props = defineProps<{
  appid: string
  /** 从模板库「去发布」跳转时预填的模板 ID */
  initialTemplateId?: string
}>()

const emit = defineEmits<{ (e: 'committed', result: ReleaseRecord): void }>()

const router = useRouter()

const templates = ref<CodeTemplate[]>([])
const listLoading = ref(false)
const submitting = ref(false)
const errorText = ref('')
const result = ref<ReleaseRecord | null>(null)

interface ExtRow {
  key: string
  value: string
}

interface ExtPageRow {
  page: string
}

const form = reactive<{
  templateId: string
  userVersion: string
  userDesc: string
  extJson: string
}>({
  templateId: '',
  userVersion: '',
  userDesc: '',
  extJson: ''
})

/** ext_json 里的自定义键值，提交时组装成 ext；extAppid 由后端按小程序 AppID 填充 */
const extRows = ref<ExtRow[]>([])
const pageRows = ref<ExtPageRow[]>([])

const canSubmit = computed(() => Boolean(props.appid && form.templateId))

function applyInitialTemplate(): void {
  const wanted = String(props.initialTemplateId ?? '')
  if (!wanted) return
  if (templates.value.some((item) => String(item.templateId) === wanted)) {
    form.templateId = wanted
  }
}

async function loadSource(): Promise<void> {
  listLoading.value = true
  try {
    const templateList = await listTemplates().catch(() => [] as CodeTemplate[])
    templates.value = Array.isArray(templateList) ? templateList : []
    if (!form.templateId && templates.value.length) {
      form.templateId = String(templates.value[0]?.templateId ?? '')
    }
    applyInitialTemplate()
  } finally {
    listLoading.value = false
  }
}

async function onSyncTemplates(): Promise<void> {
  listLoading.value = true
  errorText.value = ''
  try {
    templates.value = (await syncTemplates()) ?? []
    ElMessage.success('代码模板已同步')
  } catch (err) {
    errorText.value = describeError(err)
  } finally {
    listLoading.value = false
  }
}

function addExtRow(): void {
  extRows.value.push({ key: '', value: '' })
}

function removeExtRow(index: number): void {
  extRows.value.splice(index, 1)
}

function addPageRow(): void {
  pageRows.value.push({ page: '' })
}

function removePageRow(index: number): void {
  pageRows.value.splice(index, 1)
}

function collectExt(): Record<string, unknown> | undefined {
  const ext: Record<string, unknown> = {}
  for (const row of extRows.value) {
    const key = row.key.trim()
    if (!key) continue
    ext[key] = row.value
  }
  return Object.keys(ext).length ? ext : undefined
}

function collectExtPages(): string[] | undefined {
  const pages = pageRows.value.map((row) => row.page.trim()).filter(Boolean)
  return pages.length ? pages : undefined
}

async function onSubmit(): Promise<void> {
  if (!props.appid) {
    ElMessage.warning('请先在顶部选择要发布的小程序')
    return
  }
  if (!canSubmit.value) {
    ElMessage.warning('请选择代码模板')
    return
  }
  const userVersion = form.userVersion.trim()
  const userDesc = form.userDesc.trim()
  if (!userVersion) {
    ElMessage.warning('版本号是微信必填项，例如 1.0.3')
    return
  }
  if (userVersion.length > 64) {
    ElMessage.warning(`版本号最长 64 字符，当前 ${userVersion.length} 字符`)
    return
  }
  if (!userDesc) {
    ElMessage.warning('版本描述是微信必填项，会展示在审核页与版本列表')
    return
  }
  const extJson = form.extJson.trim()
  if (extJson) {
    try {
      JSON.parse(extJson)
    } catch {
      ElMessage.error('extJson 不是合法 JSON，请检查后重试')
      return
    }
  }

  submitting.value = true
  errorText.value = ''
  result.value = null
  try {
    const data = await commitCode(props.appid, {
      templateId: form.templateId,
      userVersion,
      userDesc,
      extJson: extJson || undefined,
      ext: collectExt(),
      extPages: collectExtPages()
    })
    result.value = data
    if (data.errcode && data.errcode !== 0) {
      errorText.value = `代码上传失败：errcode=${data.errcode}，${data.errmsg ?? ''}`
    } else {
      ElMessage.success('代码已上传到体验版')
      emit('committed', data)
    }
  } catch (err) {
    errorText.value = describeError(err)
    const detail = describeErrorDetail(err)
    if (detail) errorText.value += `（${detail}）`
  } finally {
    submitting.value = false
  }
}

watch(
  () => props.appid,
  () => {
    result.value = null
    errorText.value = ''
  }
)

onMounted(loadSource)

watch(() => props.initialTemplateId, applyInitialTemplate)
</script>

<template>
  <div class="commit-panel">
    <el-alert v-if="!appid" type="info" show-icon :closable="false" title="请先在顶部选择小程序，再上传代码。" />

    <el-form label-width="128px" label-position="right" @submit.prevent="onSubmit">
      <el-form-item label="代码模板">
        <div class="commit-panel__picker">
          <el-select
            v-model="form.templateId"
            :loading="listLoading"
            filterable
            clearable
            placeholder="选择标准代码模板"
          >
            <el-option
              v-for="item in templates"
              :key="String(item.templateId)"
              :value="String(item.templateId)"
              :label="`${item.templateId}　${item.userVersion ?? ''}　${item.userDesc ?? ''}`"
            />
          </el-select>
          <el-button :icon="Refresh" :loading="listLoading" text @click="onSyncTemplates">同步模板</el-button>
          <el-button text type="primary" @click="router.push('/templates')">模板库</el-button>
          <el-button text type="primary" @click="router.push('/drafts')">草稿箱</el-button>
        </div>
        <span class="muted commit-panel__tip">
          草稿箱里的草稿需先「转为模板」才能上传代码（微信没有直接提交草稿的接口）。
        </span>
      </el-form-item>

      <el-form-item label="版本号" required>
        <el-input v-model="form.userVersion" placeholder="例如 1.0.3，用于后台展示与回退识别" maxlength="64" />
        <span class="muted commit-panel__tip">微信必填（最长 64 字符），留空时上传代码会被拒绝。</span>
      </el-form-item>

      <el-form-item label="版本描述" required>
        <el-input
          v-model="form.userDesc"
          type="textarea"
          :rows="2"
          maxlength="200"
          show-word-limit
          placeholder="本次改动：新增活动报名、修复会员列表分页…（会展示在审核页）"
        />
      </el-form-item>

      <el-form-item label="extAppid">
        <el-input :model-value="appid || '（未选择小程序）'" readonly class="mono" />
        <span class="muted commit-panel__tip">
          由后端按当前小程序 AppID 隐式写入 ext_json.extAppid，无需手工填写。
        </span>
      </el-form-item>

      <el-form-item label="ext 自定义字段">
        <div class="commit-panel__rows">
          <div v-for="(row, index) in extRows" :key="index" class="commit-panel__row">
            <el-input v-model="row.key" placeholder="字段名，如 A" class="commit-panel__key" />
            <el-input v-model="row.value" placeholder="字段值，如 协会名称" class="commit-panel__value" />
            <el-button text type="danger" :icon="Delete" @click="removeExtRow(index)">移除</el-button>
          </div>
          <el-button text type="primary" :icon="Plus" @click="addExtRow">添加字段</el-button>
          <span class="muted commit-panel__tip">对应 ext_json.ext，模板化小程序用它注入租户配置。</span>
        </div>
      </el-form-item>

      <el-form-item label="extPages">
        <div class="commit-panel__rows">
          <div v-for="(row, index) in pageRows" :key="index" class="commit-panel__row">
            <el-input v-model="row.page" placeholder="页面路径，如 pages/index/index" class="commit-panel__value" />
            <el-button text type="danger" :icon="Delete" @click="removePageRow(index)">移除</el-button>
          </div>
          <el-button text type="primary" :icon="Plus" @click="addPageRow">添加页面</el-button>
          <span class="muted commit-panel__tip">对应 ext_json.extPages，用于把页面路径映射到分包。</span>
        </div>
      </el-form-item>

      <el-form-item label="ext.json">
        <el-input
          v-model="form.extJson"
          type="textarea"
          :rows="3"
          placeholder='选填，直接给出完整 ext_json，例如 {"extAppid":"wx0000000000000000","ext":{"A":"协会名称"}}'
          class="mono"
        />
        <span class="muted commit-panel__tip">填写后优先生效，上方的 ext / extPages 会被忽略；留空表示由后端组装。</span>
      </el-form-item>

      <el-form-item>
        <el-button
          type="primary"
          :loading="submitting"
          :disabled="!canSubmit"
          :icon="CaretRight"
          @click="onSubmit"
        >
          上传代码到体验版
        </el-button>
        <span v-if="!canSubmit" class="muted commit-panel__tip">请先选择模板</span>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="errorText"
      class="commit-panel__alert"
      type="error"
      show-icon
      :closable="false"
      title="代码上传失败"
      :description="errorText"
    />

    <div v-if="result" class="commit-panel__result">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="发布单 ID">{{ result.id }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ result.status ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="版本号">{{ result.userVersion ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="errcode / errmsg">
          <span class="mono">{{ result.errcode ?? '—' }}</span>
          <span class="muted">　{{ result.errmsg ?? '' }}</span>
        </el-descriptions-item>
      </el-descriptions>
    </div>

    <slot name="after" />
  </div>
</template>

<style scoped>
.commit-panel__picker {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.commit-panel__picker .el-select {
  flex: 1;
  min-width: 220px;
}

.commit-panel__rows {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: flex-start;
}

.commit-panel__row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.commit-panel__key {
  max-width: 180px;
}

.commit-panel__value {
  flex: 1;
}

.commit-panel__tip {
  font-size: 12px;
  line-height: 1.6;
}

.commit-panel__alert {
  margin-top: 8px;
}

.commit-panel__result {
  margin-top: 12px;
}
</style>
