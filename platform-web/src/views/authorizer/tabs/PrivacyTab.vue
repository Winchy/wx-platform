<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Check, Close, Lock, Plus, Refresh, User } from '@element-plus/icons-vue'

import {
  getPrivacySetting,
  updatePrivacySetting,
  type PrivacyDescItem,
  type PrivacyOwnerSetting,
  type PrivacySettingItem,
  type PrivacySettingResult,
  type PrivacyVersion
} from '@/api/config'
import { EmptyState } from '@/components'
import { formatDateTime } from '@/utils/format'
import { describeError } from '@/utils/request'

const props = defineProps<{ appid: string }>()

/** 微信要求「数字+天」，例如 30天，否则报 87072 */
const STORE_EXPIRE_PATTERN = /^\d+天$/

const loading = ref(false)
const saving = ref(false)
const errorText = ref('')
const version = ref<PrivacyVersion>(2)
const settingRows = ref<PrivacySettingItem[]>([])
const owner = reactive({
  notice_method: '',
  contact_phone: '',
  contact_email: '',
  contact_qq: '',
  contact_weixin: '',
  store_expire_timestamp: '',
  store_region: undefined as number | undefined
})

const codeExist = ref<number | null>(null)
const detectedKeys = ref<string[]>([])
const descList = ref<PrivacyDescItem[]>([])
const updateTime = ref<number | string | null>(null)

const isProduction = computed(() => version.value === 1)
const descMap = computed(() => new Map(descList.value.map((item) => [item.privacy_key, item.privacy_desc])))
const usedKeys = computed(() => new Set(settingRows.value.map((item) => item.privacy_key).filter(Boolean)))
const updateText = computed(() => {
  const raw = updateTime.value
  if (raw === null || raw === undefined || raw === '') return ''
  const value = Number(raw)
  if (!Number.isFinite(value)) return String(raw)
  // 秒级时间戳换算成毫秒，formatDateTime 按毫秒解析
  return formatDateTime(value < 1e11 ? value * 1000 : value, String(raw))
})

const versionHint = computed(() =>
  isProduction.value
    ? '当前查看的是线上已生效的指引。微信侧只允许改「收集方信息」，信息类型与用途需切回开发版修改。'
    : '开发版指引改完后，需要重新提交审核并发布才会对线上用户生效。'
)

function descOf(key: string): string {
  return descMap.value.get(key) ?? ''
}

function labelOf(key: string): string {
  return descOf(key) || key
}

/** 代码检测到的类型是审核对账依据，点一下补一行；现网版本只读，要改得切回开发版。 */
function addDetected(key: string): void {
  if (isProduction.value) {
    ElMessage.info('现网版本仅供查看，请切回开发版调整信息清单')
    return
  }
  if (usedKeys.value.has(key)) {
    ElMessage.info(`「${labelOf(key)}」已在列表中`)
    return
  }
  settingRows.value = [...settingRows.value, { privacy_key: key, privacy_text: '' }]
}

function fillOwner(source: PrivacyOwnerSetting | null | undefined): void {
  owner.notice_method = source?.notice_method ?? ''
  owner.contact_phone = source?.contact_phone ?? ''
  owner.contact_email = source?.contact_email ?? ''
  owner.contact_qq = source?.contact_qq ?? ''
  owner.contact_weixin = source?.contact_weixin ?? ''
  owner.store_expire_timestamp = source?.store_expire_timestamp ?? ''
  owner.store_region = typeof source?.store_region === 'number' ? source.store_region : undefined
}

async function load(): Promise<void> {
  if (!props.appid) return
  loading.value = true
  errorText.value = ''
  try {
    const data: PrivacySettingResult = await getPrivacySetting(props.appid, version.value)
    codeExist.value = data?.code_exist ?? null
    detectedKeys.value = data?.privacy_list ?? []
    descList.value = data?.privacy_desc?.privacy_desc_list ?? []
    updateTime.value = data?.update_time ?? null
    settingRows.value = (data?.setting_list ?? []).map((item) => ({
      privacy_key: item.privacy_key ?? '',
      privacy_text: item.privacy_text ?? ''
    }))
    fillOwner(data?.owner_setting)
  } catch (err) {
    errorText.value = describeError(err)
    settingRows.value = []
    detectedKeys.value = []
  } finally {
    loading.value = false
  }
}

function addRow(): void {
  settingRows.value = [...settingRows.value, { privacy_key: '', privacy_text: '' }]
}

function removeRow(index: number): void {
  const rows = [...settingRows.value]
  rows.splice(index, 1)
  settingRows.value = rows
}

function resetForm(): void {
  void load()
}

function validate(): string {
  if (!owner.notice_method.trim()) return '请填写通知方式（notice_method），例如「弹窗」「公告」'
  const contacts = [owner.contact_phone, owner.contact_email, owner.contact_qq, owner.contact_weixin]
  if (!contacts.some((value) => value.trim())) return '手机号 / 邮箱 / QQ / 微信号四种联系方式至少要填一种'
  const expire = owner.store_expire_timestamp.trim()
  if (expire && !STORE_EXPIRE_PATTERN.test(expire)) return '存储期限需写成「数字+天」，例如 30天，否则微信会报 87072'
  if (isProduction.value) return ''
  const keys = new Set<string>()
  for (const row of settingRows.value) {
    const key = row.privacy_key.trim()
    if (!key) return '信息类型不能为空，请从下拉中选择或自定义一个 privacy_key'
    if (!row.privacy_text.trim()) return `请填写「${labelOf(key)}」的收集用途`
    if (keys.has(key)) return `信息类型「${labelOf(key)}」重复`
    keys.add(key)
  }
  if (!settingRows.value.length) return '开发版至少需要配置一项收集的信息'
  return ''
}

async function onSave(): Promise<void> {
  if (!props.appid) {
    ElMessage.warning('请先选择小程序')
    return
  }
  const message = validate()
  if (message) {
    ElMessage.warning(message)
    return
  }
  const ownerSetting: PrivacyOwnerSetting = {
    notice_method: owner.notice_method.trim(),
    store_expire_timestamp: owner.store_expire_timestamp.trim() || undefined,
    store_region: owner.store_region
  }
  for (const field of ['contact_phone', 'contact_email', 'contact_qq', 'contact_weixin'] as const) {
    const value = owner[field].trim()
    if (value) ownerSetting[field] = value
  }
  saving.value = true
  errorText.value = ''
  try {
    await updatePrivacySetting(props.appid, {
      privacyVer: version.value,
      // privacy_ver=1 传 setting_list 会被微信拒绝
      settingList: isProduction.value ? undefined : settingRows.value.map((row) => ({
        privacy_key: row.privacy_key.trim(),
        privacy_text: row.privacy_text.trim()
      })),
      ownerSetting
    })
    ElMessage.success(isProduction.value ? '收集方信息已更新到现网版本' : '开发版隐私指引已保存，提审发布后生效')
    await load()
  } catch (err) {
    errorText.value = describeError(err)
    ElMessage.error(describeError(err))
  } finally {
    saving.value = false
  }
}

watch(() => props.appid, load)
watch(version, load)
onMounted(load)

defineExpose({ reload: load })
</script>

<template>
  <div class="privacy-tab" v-loading="loading">
    <section class="panel panel--flat">
      <h3 class="panel__title">
        <el-icon><Lock /></el-icon>
        <span>用户隐私保护指引</span>
        <span class="muted privacy-tab__time" v-if="updateText">更新于 {{ updateText }}</span>
        <el-button class="panel__more" text :loading="loading" @click="load">
          <el-icon><Refresh /></el-icon>
          <span>刷新</span>
        </el-button>
      </h3>

      <div class="privacy-tab__version">
        <el-radio-group v-model="version">
          <el-radio-button :value="2">开发版</el-radio-button>
          <el-radio-button :value="1">现网版本</el-radio-button>
        </el-radio-group>
        <span class="muted privacy-tab__hint">{{ versionHint }}</span>
      </div>

      <el-alert v-if="errorText" type="error" show-icon :closable="false" :title="errorText" class="mb-12" />

      <el-alert
        v-if="codeExist === 0"
        type="warning"
        show-icon
        :closable="false"
        title="微信未检测到已上传的代码（code_exist=0），因此列不出代码里用到的信息类型。请先在「上传代码」里 commit 一版再回来配置。"
        class="mb-12"
      />
    </section>

    <section class="panel panel--flat">
      <h3 class="panel__title">
        <el-icon><Check /></el-icon>
        <span>收集的信息与用途</span>
      </h3>

      <div class="privacy-tab__detected" v-if="detectedKeys.length">
        <span class="muted privacy-tab__hint">代码里检测到的类型：</span>
        <el-tag
          v-for="key in detectedKeys"
          :key="key"
          class="privacy-tab__detected-tag"
          :type="usedKeys.has(key) ? 'success' : 'warning'"
          @click="addDetected(key)"
        >
          {{ descOf(key) || key }}
        </el-tag>
      </div>
      <p class="muted privacy-tab__hint mb-12" v-else>
        未从代码里检测到信息类型；若小程序确实会收集用户信息，请手动添加对应类型与用途，否则提审会被驳回。
      </p>

      <el-table v-if="settingRows.length" :data="settingRows" border size="small">
        <el-table-column label="信息类型" min-width="220">
          <template #default="{ $index }: { $index: number }">
            <strong v-if="isProduction">{{ labelOf(settingRows[$index].privacy_key) }}</strong>
            <template v-else>
              <el-select
                v-model="settingRows[$index].privacy_key"
                filterable
                allow-create
                default-first-option
                placeholder="选择或自定义 privacy_key"
                style="width: 100%"
              >
                <el-option v-for="item in descList" :key="item.privacy_key" :value="item.privacy_key" :label="item.privacy_desc">
                  <span class="mono">{{ item.privacy_key }}</span>
                  <span class="muted privacy-tab__option">{{ item.privacy_desc }}</span>
                </el-option>
              </el-select>
              <span v-if="settingRows[$index].privacy_key && !descOf(settingRows[$index].privacy_key)" class="muted privacy-tab__custom">
                自定义类型
              </span>
            </template>
          </template>
        </el-table-column>
        <el-table-column label="收集用途（privacy_text）" min-width="300">
          <template #default="{ $index }: { $index: number }">
            <span v-if="isProduction">{{ settingRows[$index].privacy_text || '—' }}</span>
            <el-input
              v-else
              v-model="settingRows[$index].privacy_text"
              maxlength="200"
              placeholder="例如：用于登记协会会员信息并生成会员证"
            />
          </template>
        </el-table-column>
        <el-table-column v-if="!isProduction" label="操作" width="80" align="right">
          <template #default="{ $index }: { $index: number }">
            <el-button text type="danger" size="small" @click="removeRow($index)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <EmptyState
        v-else-if="isProduction"
        compact
        description="现网版本还没有生效的隐私指引"
        sub-description="在「开发版」配置好信息清单与收集方信息，提审发布后这里会显示线上已生效的内容。"
      />

      <EmptyState
        v-else
        compact
        description="尚未配置收集的信息"
        sub-description="小程序用到的每一项用户信息都需要在此声明用途，缺失会被审核驳回。"
      />

      <el-button v-if="!isProduction" class="privacy-tab__add" :icon="Plus" text type="primary" @click="addRow">
        添加一项信息
      </el-button>

      <p class="muted privacy-tab__tip" v-if="!isProduction">
        用途直接写内容即可，微信端会自动加「为了」，无需重复书写。
      </p>
    </section>

    <section class="panel panel--flat">
      <h3 class="panel__title">
        <el-icon><User /></el-icon>
        <span>收集方（开发者）信息</span>
      </h3>

      <el-form label-width="130px" label-position="left" class="privacy-tab__form">
        <el-form-item label="通知方式" required>
          <el-input v-model="owner.notice_method" placeholder="例如：弹窗提示" style="width: 260px" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="owner.contact_phone" placeholder="四种联系方式至少填一种" style="width: 260px" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="owner.contact_email" placeholder="例如：privacy@example.org" style="width: 260px" />
        </el-form-item>
        <el-form-item label="QQ">
          <el-input v-model="owner.contact_qq" style="width: 260px" />
        </el-form-item>
        <el-form-item label="微信号">
          <el-input v-model="owner.contact_weixin" style="width: 260px" />
        </el-form-item>
        <el-form-item label="存储期限">
          <el-input v-model="owner.store_expire_timestamp" placeholder="留空则展示微信默认承诺文案" style="width: 260px" />
        </el-form-item>
        <el-form-item label="存储地区">
          <el-input-number v-model="owner.store_region" :min="0" :controls="false" placeholder="境外主体需填写" />
        </el-form-item>
      </el-form>

      <div class="privacy-tab__actions">
        <el-button type="primary" :loading="saving" :disabled="!appid" @click="onSave">
          {{ isProduction ? '保存收集方信息' : '保存开发版指引' }}
        </el-button>
        <el-button text :disabled="saving" @click="resetForm">
          <el-icon><Close /></el-icon>
          <span>还原</span>
        </el-button>
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

.privacy-tab__time {
  font-size: 12px;
}

.privacy-tab__version {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.privacy-tab__hint {
  font-size: 12px;
  line-height: 1.6;
}

.privacy-tab__detected {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}

.privacy-tab__detected-tag {
  cursor: pointer;
}

.privacy-tab__option {
  margin-left: 8px;
  font-size: 12px;
}

.privacy-tab__custom {
  font-size: 12px;
}

.privacy-tab__add {
  margin-top: 10px;
}

.privacy-tab__tip {
  margin: 10px 0 0;
  font-size: 12px;
}

.privacy-tab__form {
  max-width: 520px;
}

.privacy-tab__actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
}
</style>
