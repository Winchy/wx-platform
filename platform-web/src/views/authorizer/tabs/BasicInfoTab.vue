<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules, type UploadRequestHandler } from 'element-plus'
import { Check, CircleCheck, Delete, Edit, Link, Picture, Plus, Refresh, Upload } from '@element-plus/icons-vue'

import {
  checkNickname,
  getAuthorizerQrcode,
  getBasicInfo,
  updateAvatar,
  updateNickname,
  updateSignature,
  uploadNicknameMaterial,
  type AuthorizerQrcode,
  type BasicInfo,
  type NicknameCheckResult
} from '@/api/config'
import { EmptyState, FormDialog, JsonBlock } from '@/components'
import { copyToClipboard, displayValue, formatDateTime } from '@/utils/format'
import { describeError } from '@/utils/request'

const props = defineProps<{ appid: string }>()

const loading = ref(false)
const loadError = ref('')
const info = ref<BasicInfo | null>(null)

const qrcode = ref<AuthorizerQrcode | null>(null)
const qrcodeLoading = ref(false)
const qrcodeError = ref('')

const nicknameDialog = ref(false)
const signatureDialog = ref(false)
const submitting = ref(false)
const checking = ref(false)
const uploading = ref(false)
const checkResult = ref<NicknameCheckResult | null>(null)
const checkError = ref('')

const nicknameFormRef = ref<FormInstance>()
const signatureFormRef = ref<FormInstance>()

/** 材料填的都是新增临时素材返回的 media_id，微信侧 3 天后失效 */
const nicknameForm = reactive<{
  nickname: string
  idCard: string
  license: string
  otherStuff: string[]
}>({
  nickname: '',
  idCard: '',
  license: '',
  otherStuff: []
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

/** 微信只给 naming_other_stuff_1..5 五个槽位 */
const OTHER_STUFF_MAX = 5

const checkSummary = computed(() => {
  if (!checkResult.value) return ''
  return checkResult.value.hitCondition
    ? '命中关键词：需要提交资质材料，材料齐全后进入微信审核'
    : '未命中关键词：无需资质材料，可直接提交改名'
})

const checkTone = computed<'warning' | 'success'>(() =>
  checkResult.value?.hitCondition ? 'warning' : 'success'
)

/** open_verify / open_scanned_search：微信返回 -1 表示资料未完善 */
function switchText(value: number | null | undefined, on: string, off: string): string {
  if (value === null || value === undefined || value === -1) return '未返回'
  return Number(value) === 1 ? on : off
}

const openVerifyText = computed(() => switchText(info.value?.openVerify, '已认证', '未认证'))
const openScannedSearchText = computed(() =>
  switchText(info.value?.openScannedSearch, '已开启（可被搜索）', '已关闭')
)

/** apiRenameSupported 为 false 说明是普通方式注册，微信必定拒绝接口改名（91001）；null 是还没刷新过资料，不拦 */
const renameBlocked = computed(() => info.value?.apiRenameSupported === false)

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
  await loadQrcode()
}

/**
 * 小程序码由后端代取并保存一份（微信图片 CDN 有 Referer 防盗链，浏览器直连只会拿到「未经允许不可引用」
 * 的占位图）。默认直接取后端已存的那份，refresh=true 才让后端重新去微信取。
 */
async function loadQrcode(refresh = false): Promise<void> {
  qrcode.value = null
  qrcodeError.value = ''
  if (!props.appid || !info.value?.qrCodeUrl) {
    return
  }
  qrcodeLoading.value = true
  try {
    qrcode.value = await getAuthorizerQrcode(props.appid, refresh)
  } catch (error) {
    qrcodeError.value = describeError(error)
  } finally {
    qrcodeLoading.value = false
  }
}

function openNicknameDialog(): void {
  nicknameForm.nickname = info.value?.nickname ?? ''
  nicknameForm.idCard = ''
  nicknameForm.license = ''
  nicknameForm.otherStuff = []
  checkResult.value = null
  checkError.value = ''
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
  checkError.value = ''
  try {
    checkResult.value = await checkNickname(props.appid, nickname)
  } catch (error) {
    checkResult.value = null
    checkError.value = describeError(error)
  } finally {
    checking.value = false
  }
}

/** 材料图片走「新增临时素材」换 media_id，成功后回填到触发上传的那个字段 */
function materialUpload(target: 'idCard' | 'license' | number): UploadRequestHandler {
  return async (options) => {
    uploading.value = true
    try {
      const mediaId = await uploadNicknameMaterial(props.appid, options.file as File)
      if (typeof target === 'number') {
        nicknameForm.otherStuff[target] = mediaId
      } else {
        nicknameForm[target] = mediaId
      }
      ElMessage.success('已上传，media_id 已填入（微信侧 3 天后失效）')
    } catch (error) {
      ElMessage.error(describeError(error))
      throw error
    } finally {
      uploading.value = false
    }
  }
}

async function submitNickname(): Promise<void> {
  if (!nicknameFormRef.value) return
  const valid = await nicknameFormRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    const result = await updateNickname(props.appid, {
      nickname: nicknameForm.nickname.trim(),
      idCard: nicknameForm.idCard.trim(),
      license: nicknameForm.license.trim(),
      namingOtherStuff: nicknameForm.otherStuff.map((item) => item.trim()).filter(Boolean)
    })
    ElMessage.success(
      result.auditId ? `已提交微信审核，审核单号 ${result.auditId}` : '名称已直接生效，无需审核'
    )
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

/** 微信对头像图片的限制：2M 以内，PNG/JPG/GIF/BMP */
const AVATAR_MAX_BYTES = 2 * 1024 * 1024

const avatarUpload: UploadRequestHandler = async (options) => {
  const file = options.file as File
  if (file.size > AVATAR_MAX_BYTES) {
    ElMessage.warning(`头像图片不能超过 2M，当前 ${Math.round(file.size / 1024)}K，请先压缩成正方形图片`)
    return
  }
  uploading.value = true
  try {
    await updateAvatar(props.appid, file)
    ElMessage.success('头像已更新，微信侧生效可能有延迟')
    await load()
  } catch (error) {
    ElMessage.error(describeError(error))
    throw error
  } finally {
    uploading.value = false
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
            accept="image/png,image/jpeg,image/gif,image/bmp"
            :disabled="uploading"
          >
            <el-button size="small" :loading="uploading" :icon="Upload">更换头像</el-button>
          </el-upload>
          <p class="muted basic-info__avatar-tip">支持 PNG/JPG/GIF/BMP，不超过 2M，实际头像为正方形</p>
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

        <div v-loading="qrcodeLoading" class="basic-info__qrcode">
          <el-image
            v-if="qrcode"
            :src="qrcode.dataUrl"
            :preview-src-list="[qrcode.dataUrl]"
            :initial-index="0"
            preview-teleported
            fit="contain"
            alt="小程序码"
            class="basic-info__qrcode-img"
          />
          <div v-else class="basic-info__qrcode-error">
            <el-icon><Picture /></el-icon>
            <span>{{ qrcodeError ? '小程序码取图失败' : '暂无小程序码' }}</span>
          </div>
          <div v-if="info.qrCodeUrl" class="basic-info__qrcode-actions">
            <el-button text size="small" :loading="qrcodeLoading" :icon="Refresh" @click="loadQrcode(true)">重新取图</el-button>
            <el-button text size="small" :icon="Link" @click="copyQrCodeUrl">复制图片链接</el-button>
          </div>
          <p v-if="qrcode" class="muted basic-info__qrcode-reason">
            {{ qrcode.source === 'local' ? '后端保存于' : '刚从微信取回，存于' }} {{ formatDateTime(qrcode.savedAt) }}
          </p>
          <p
            v-if="qrcode?.staleReason"
            class="basic-info__qrcode-reason"
            style="color: var(--el-color-warning)"
          >
            微信这次取图失败（{{ qrcode.staleReason }}），上面显示的是后端此前保存的小程序码。
          </p>
          <p v-if="qrcodeError" class="muted basic-info__qrcode-reason">{{ qrcodeError }}</p>
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
          <li>
            名称是否要提交资质材料，取决于「占用检查」有没有命中关键词：未命中直接改名即可，命中则需上传
            身份证（个人主体）或营业执照/组织机构代码证（组织主体），材料 media_id 由界面上的「上传图片」换取，
            在微信侧只保存 3 天。
          </li>
          <li>改名接口属于权限集 id 30，未授权时微信回 48001，需要管理员重新扫码授权并勾上它。</li>
          <li>改名额度每个自然年 2 次（1 月 1 日与微信认证审核通过后重置），用尽后只能靠发起微信认证改名。</li>
          <li>简介每月可修改次数有限，提交后以微信侧审核结果为准。</li>
          <li>
            头像分两步：先把图片传成「新增临时素材」换 <span class="mono">media_id</span>，再带裁剪框提交；
            别的途径拿到的素材 id 微信不认（40007）。图片 2M 以内，一年可申请修改 5 次。
          </li>
          <li>
            小程序码由后端取回并保存一份再显示：微信把图片放在自己的图片域名（<span class="mono">mmbiz.qpic.cn</span>）上并做了
            Referer 防盗链，网页里直接引用只会拿到「此图片来自微信公众平台 未经允许不可引用」的占位图。
            打开页面用的是后端已保存的那份，点「重新取图」才重新问微信要一次；微信一时取不到时仍显示已保存的这份，
            并在下方写明失败原因。「复制图片链接」给出的仍是微信原始直链，粘贴到浏览器地址栏能直接打开。
          </li>
        </ul>
      </div>
    </template>

    <EmptyState v-else-if="!loading" description="暂无基本信息" :sub-description="loadError" />

    <FormDialog
      v-model="nicknameDialog"
      title="修改小程序名称"
      confirm-text="提交修改"
      :loading="submitting"
      :disabled="renameBlocked"
      @confirm="submitNickname"
    >
      <el-alert
        v-if="renameBlocked"
        type="warning"
        show-icon
        :closable="false"
        title="该小程序不支持通过第三方平台改名"
        description="微信只允许「通过 API 注册」的小程序由第三方平台修改名称。请小程序管理员登录微信公众平台，在「设置 → 基本设置 → 名称」中申请修改。"
        class="basic-info__check"
      />
      <el-form ref="nicknameFormRef" :model="nicknameForm" :rules="nicknameRules" label-width="110px">
        <el-form-item label="新名称" prop="nickname">
          <el-input v-model="nicknameForm.nickname" placeholder="例如：某某市家具行业协会" maxlength="30" show-word-limit />
        </el-form-item>
        <el-form-item label="占用检查">
          <el-button :loading="checking" :icon="CircleCheck" @click="onCheckNickname">检查名称是否可用</el-button>
        </el-form-item>
        <el-form-item v-if="checkResult || checkError" label=" ">
          <el-alert
            v-if="checkResult"
            :type="checkTone"
            show-icon
            :closable="false"
            :title="checkSummary"
            :description="checkResult.wording || ''"
            class="basic-info__check"
          />
          <el-alert
            v-else
            type="error"
            show-icon
            :closable="false"
            :title="`名称检测失败：${checkError}`"
            class="basic-info__check"
          />
        </el-form-item>
        <el-form-item label="身份证照片">
          <div class="basic-info__material">
            <el-input
              v-model="nicknameForm.idCard"
              placeholder="media_id，个人主体改名必填"
              clearable
              class="mono"
            />
            <el-upload
              :show-file-list="false"
              :http-request="materialUpload('idCard')"
              accept="image/png,image/jpeg,image/gif"
              :disabled="uploading"
            >
              <el-button :loading="uploading" :icon="Upload">上传图片</el-button>
            </el-upload>
          </div>
        </el-form-item>
        <el-form-item label="营业执照">
          <div class="basic-info__material">
            <el-input
              v-model="nicknameForm.license"
              placeholder="media_id，组织主体（协会/商会/企业）必填"
              clearable
              class="mono"
            />
            <el-upload
              :show-file-list="false"
              :http-request="materialUpload('license')"
              accept="image/png,image/jpeg,image/gif"
              :disabled="uploading"
            >
              <el-button :loading="uploading" :icon="Upload">上传图片</el-button>
            </el-upload>
          </div>
          <p class="muted basic-info__tip">组织机构代码证同样填在这里，按微信要求二选一上传。</p>
        </el-form-item>
        <el-form-item
          v-for="(_, index) in nicknameForm.otherStuff"
          :key="index"
          :label="`其他材料 ${index + 1}`"
        >
          <div class="basic-info__material">
            <el-input
              v-model="nicknameForm.otherStuff[index]"
              placeholder="商标注册证、社会团体登记证书等 media_id"
              clearable
              class="mono"
            />
            <el-upload
              :show-file-list="false"
              :http-request="materialUpload(index)"
              accept="image/png,image/jpeg,image/gif"
              :disabled="uploading"
            >
              <el-button :loading="uploading" :icon="Upload">上传</el-button>
            </el-upload>
            <el-button text :icon="Delete" @click="nicknameForm.otherStuff.splice(index, 1)">删除</el-button>
          </div>
        </el-form-item>
        <el-form-item v-if="nicknameForm.otherStuff.length < OTHER_STUFF_MAX" label=" ">
          <el-button text type="primary" :icon="Plus" @click="nicknameForm.otherStuff.push('')">
            添加其他证明材料
          </el-button>
        </el-form-item>
      </el-form>
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

.basic-info__qrcode-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.basic-info__qrcode-reason {
  max-width: 230px;
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  text-align: center;
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
  width: 100%;
}

.basic-info__material {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.basic-info__material .el-input {
  flex: 1;
  min-width: 0;
}

.basic-info__tip {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
}
</style>
