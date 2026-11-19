<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules, type UploadRequestHandler } from 'element-plus'
import { Check, CircleCheck, Edit, Picture, Upload } from '@element-plus/icons-vue'

import {
  checkNickname,
  getBasicInfo,
  updateAvatar,
  updateNickname,
  updateSignature,
  type BasicInfo
} from '@/api/config'
import { EmptyState, FormDialog, JsonBlock } from '@/components'
import { copyToClipboard, displayValue } from '@/utils/format'
import { describeError } from '@/utils/request'

const props = defineProps<{ appid: string }>()

const loading = ref(false)
const loadError = ref('')
const info = ref<BasicInfo | null>(null)

const nicknameDialog = ref(false)
const signatureDialog = ref(false)
const submitting = ref(false)
const checking = ref(false)
const checkResult = ref('')

const nicknameFormRef = ref<FormInstance>()
const signatureFormRef = ref<FormInstance>()

/** credentials 会被后端原样合并进微信 setnickname 请求体 */
const nicknameForm = reactive<{
  nickname: string
  nameType: number | undefined
  credentialText: string
  enterpriseName: string
  orgName: string
}>({
  nickname: '',
  nameType: undefined,
  credentialText: '',
  enterpriseName: '',
  orgName: ''
})

const signatureForm = reactive<{ signature: string }>({ signature: '' })

const nicknameRules: FormRules = {
  nickname: [
    { required: true, message: '请输入小程序名称', trigger: 'blur' },
    { min: 4, max: 30, message: '名称长度需在 4 - 30 个字符之间', trigger: 'blur' }
  ]
}

const signatureRules: FormRules = {
  signature: [
    { required: true, message: '请输入功能简介', trigger: 'blur' },
    { max: 120, message: '简介不超过 120 个字符', trigger: 'blur' }
  ]
}

const nameTypeOptions = [
  { value: 1, label: '公众号/小程序名称（需资质材料）' },
  { value: 2, label: '企业简称' },
  { value: 3, label: '商标名称' },
  { value: 4, label: '平台名称（协会/商会等）' }
]

const credentialIds = computed(() =>
  nicknameForm.credentialText
    .split(/[,，\s]+/)
    .map((item) => item.trim())
    .filter(Boolean)
)

const credentialsPayload = computed<Record<string, unknown>>(() => {
  const payload: Record<string, unknown> = {}
  if (nicknameForm.nameType !== undefined) payload.name_type = nicknameForm.nameType
  if (nicknameForm.enterpriseName.trim()) payload.enterprise_name = nicknameForm.enterpriseName.trim()
  if (nicknameForm.orgName.trim()) payload.org_name = nicknameForm.orgName.trim()
  if (credentialIds.value.length) payload.credential_media_id = credentialIds.value
  return payload
})

/** open_verify / open_scanned_search：微信返回 -1 表示资料未完善 */
function switchText(value: number | null | undefined, on: string, off: string): string {
  if (value === null || value === undefined || value === -1) return '未返回'
  return Number(value) === 1 ? on : off
}

const openVerifyText = computed(() => switchText(info.value?.openVerify, '已认证', '未认证'))
const openScannedSearchText = computed(() =>
  switchText(info.value?.openScannedSearch, '已开启（可被搜索）', '已关闭')
)

const rawText = computed(() => (info.value?.raw ? JSON.stringify(info.value.raw) : ''))

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    info.value = await getBasicInfo(props.appid)
  } catch (error) {
    info.value = null
    loadError.value = describeError(error)
  } finally {
    loading.value = false
  }
}

function openNicknameDialog(): void {
  nicknameForm.nickname = info.value?.nickname ?? ''
  nicknameForm.credentialText = ''
  nicknameForm.nameType = undefined
  nicknameForm.enterpriseName = ''
  nicknameForm.orgName = ''
  checkResult.value = ''
  nicknameDialog.value = true
}

function openSignatureDialog(): void {
  signatureForm.signature = info.value?.signature ?? ''
  signatureDialog.value = true
}

async function onCheckNickname(): Promise<void> {
  const nickname = nicknameForm.nickname.trim()
  if (!nickname) {
    ElMessage.warning('请先填写要检查的小程序名称')
    return
  }
  checking.value = true
  try {
    const result = await checkNickname(props.appid, nickname)
    checkResult.value = typeof result === 'string' ? result : JSON.stringify(result ?? {})
  } catch (error) {
    checkResult.value = describeError(error)
  } finally {
    checking.value = false
  }
}

async function submitNickname(): Promise<void> {
  if (!nicknameFormRef.value) return
  const valid = await nicknameFormRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await updateNickname(props.appid, {
      nickname: nicknameForm.nickname.trim(),
      credentials: credentialsPayload.value
    })
    ElMessage.success('小程序名称已提交修改')
    nicknameDialog.value = false
    await load()
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    submitting.value = false
  }
}

async function submitSignature(): Promise<void> {
  if (!signatureFormRef.value) return
  const valid = await signatureFormRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await updateSignature(props.appid, { signature: signatureForm.signature.trim() })
    ElMessage.success('功能简介已提交修改')
    signatureDialog.value = false
    await load()
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    submitting.value = false
  }
}

const avatarUpload: UploadRequestHandler = async (options) => {
  const file = options.file as File
  try {
    await updateAvatar(props.appid, file)
    ElMessage.success('头像已更新，微信侧生效可能有延迟')
    await load()
  } catch (error) {
    ElMessage.error(describeError(error))
    throw error
  }
}

async function copyQrCodeUrl(): Promise<void> {
  const url = info.value?.qrCodeUrl
  if (!url) return
  const ok = await copyToClipboard(url)
  ElMessage[ok ? 'success' : 'error'](ok ? '小程序码链接已复制' : '复制失败')
}

watch(() => props.appid, load)
onMounted(load)
defineExpose({ reload: load })
</script>

<template>
  <div v-loading="loading" class="basic-info">
    <el-alert
      v-if="loadError"
      type="error"
      show-icon
      :closable="false"
      :title="`基本信息获取失败：${loadError}`"
      class="mb-14"
    />

    <template v-if="info">
      <div class="basic-info__top">
        <div class="basic-info__avatar">
          <el-avatar :size="72" :src="info.avatarUrl ?? undefined">
            {{ (info.nickname || appid).slice(0, 1) }}
          </el-avatar>
          <el-upload
            :show-file-list="false"
            :http-request="avatarUpload"
            accept="image/png,image/jpeg"
            :disabled="submitting"
          >
            <el-button size="small" :icon="Upload">更换头像</el-button>
          </el-upload>
          <p class="muted basic-info__avatar-tip">multipart 字段名 file，支持 jpg/png</p>
        </div>

        <div class="basic-info__summary">
          <div class="basic-info__line">
            <span class="basic-info__label">名称</span>
            <span class="strong">{{ displayValue(info.nickname) }}</span>
            <el-button text type="primary" size="small" :icon="Edit" @click="openNicknameDialog">修改</el-button>
          </div>
          <div class="basic-info__line">
            <span class="basic-info__label">微信号</span>
            <span class="mono">{{ displayValue(info.userName) }}</span>
          </div>
          <div class="basic-info__line">
            <span class="basic-info__label">功能简介</span>
            <span class="basic-info__text">{{ displayValue(info.signature, '未填写') }}</span>
            <el-button text type="primary" size="small" :icon="Edit" @click="openSignatureDialog">修改</el-button>
          </div>
          <div class="basic-info__line">
            <span class="basic-info__label">被搜索</span>
            <span>{{ openScannedSearchText }}</span>
          </div>
          <div class="basic-info__line">
            <span class="basic-info__label">认证状态</span>
            <span>{{ openVerifyText }}</span>
          </div>
          <div class="basic-info__line">
            <span class="basic-info__label">AppID</span>
            <span class="mono">{{ appid }}</span>
          </div>
        </div>

        <div class="basic-info__qrcode">
          <el-image v-if="info.qrCodeUrl" :src="info.qrCodeUrl" fit="contain" class="basic-info__qrcode-img">
            <template #error>
              <div class="basic-info__qrcode-error">
                <el-icon><Picture /></el-icon>
                <span>小程序码加载失败</span>
              </div>
            </template>
          </el-image>
          <div v-else class="basic-info__qrcode-error">
            <el-icon><Picture /></el-icon>
            <span>后端未返回小程序码</span>
          </div>
          <el-button v-if="info.qrCodeUrl" text size="small" @click="copyQrCodeUrl">复制图片链接</el-button>
        </div>
      </div>

      <div v-if="rawText" class="panel panel--inner">
        <h3 class="panel__title">微信原始字段</h3>
        <JsonBlock :value="rawText" :max-height="200" />
      </div>

      <div class="panel panel--inner">
        <h3 class="panel__title">
          <el-icon><Check /></el-icon>
          <span>修改说明</span>
        </h3>
        <ul class="basic-info__tips">
          <li>名称修改受微信侧次数限制，需上传名称占位/资质材料 media_id（多个用逗号分隔）。</li>
          <li>简介每月可修改次数有限，提交后以微信侧审核结果为准。</li>
          <li>头像为 multipart 上传，字段名 <span class="mono">file</span>，后端会先转成 mediaid 再提交。</li>
        </ul>
      </div>
    </template>

    <EmptyState v-else-if="!loading" description="暂无基本信息" :sub-description="loadError" />

    <FormDialog
      v-model="nicknameDialog"
      title="修改小程序名称"
      confirm-text="提交修改"
      :loading="submitting"
      @confirm="submitNickname"
    >
      <el-form ref="nicknameFormRef" :model="nicknameForm" :rules="nicknameRules" label-width="110px">
        <el-form-item label="新名称" prop="nickname">
          <el-input v-model="nicknameForm.nickname" placeholder="例如：某某市家具行业协会" maxlength="30" show-word-limit />
        </el-form-item>
        <el-form-item label="名称类型">
          <el-select v-model="nicknameForm.nameType" placeholder="按微信侧要求选择（可不填）" clearable>
            <el-option v-for="item in nameTypeOptions" :key="item.value" :value="item.value" :label="item.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="资质材料 ID">
          <el-input v-model="nicknameForm.credentialText" placeholder="media_id，多个用逗号分隔" />
        </el-form-item>
        <el-form-item label="企业名称">
          <el-input v-model="nicknameForm.enterpriseName" placeholder="选填" />
        </el-form-item>
        <el-form-item label="组织机构代码">
          <el-input v-model="nicknameForm.orgName" placeholder="选填" />
        </el-form-item>
        <el-form-item label="占用检查">
          <el-button :loading="checking" :icon="CircleCheck" @click="onCheckNickname">检查名称是否可用</el-button>
        </el-form-item>
      </el-form>
      <p v-if="checkResult" class="muted basic-info__check mono">{{ checkResult }}</p>
    </FormDialog>

    <FormDialog
      v-model="signatureDialog"
      title="修改功能简介"
      confirm-text="提交修改"
      :loading="submitting"
      @confirm="submitSignature"
    >
      <el-form ref="signatureFormRef" :model="signatureForm" :rules="signatureRules" label-width="90px">
        <el-form-item label="简介" prop="signature">
          <el-input
            v-model="signatureForm.signature"
            type="textarea"
            :rows="3"
            maxlength="120"
            show-word-limit
            placeholder="一句话说明这个协会小程序提供什么服务"
          />
        </el-form-item>
      </el-form>
    </FormDialog>
  </div>
</template>

<style scoped>
.mb-14 {
  margin-bottom: 14px;
}

.basic-info__top {
  display: flex;
  gap: 24px;
  flex-wrap: wrap;
  align-items: flex-start;
}

.basic-info__avatar {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  min-width: 120px;
}

.basic-info__avatar-tip {
  margin: 0;
  font-size: 12px;
}

.basic-info__summary {
  flex: 1;
  min-width: 300px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.basic-info__line {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.basic-info__label {
  width: 72px;
  flex-shrink: 0;
  color: var(--app-text-muted);
  font-size: 13px;
}

.basic-info__text {
  max-width: 520px;
}

.basic-info__qrcode {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.basic-info__qrcode-img {
  width: 150px;
  height: 150px;
  border: 1px solid var(--app-border-color);
  border-radius: 8px;
}

.basic-info__qrcode-error {
  width: 150px;
  height: 150px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px dashed var(--app-border-color);
  border-radius: 8px;
  color: var(--app-text-muted);
  font-size: 12px;
}

.panel--inner {
  margin-top: 18px;
}

.basic-info__tips {
  margin: 0;
  padding-left: 18px;
  color: #4b5563;
  font-size: 13px;
  line-height: 1.9;
}

.basic-info__check {
  margin: 0 0 4px;
  font-size: 12px;
  word-break: break-all;
}
</style>
