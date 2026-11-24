<script setup lang="ts">
import { onBeforeUnmount, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Iphone, Lock, Message, Promotion, User } from '@element-plus/icons-vue'

import type { LoginParams } from '@/api/auth'
import { sendLoginCode } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { cooldownSecondsOf, describeError } from '@/utils/request'
import { normalizePhone } from '@/utils/phone'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

type LoginMode = 'password' | 'sms'

const mode = ref<LoginMode>('password')

const formRef = ref<FormInstance>()
const loading = ref(false)
const errorMessage = ref('')

const form = reactive<LoginParams>({ username: '', password: '' })

const rules: FormRules<LoginParams> = {
  username: [{ required: true, message: '请输入登录账号', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入登录密码', trigger: 'blur' },
    { min: 4, message: '密码至少 4 位', trigger: 'blur' }
  ]
}

async function onSubmit(): Promise<void> {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    await auth.login({ username: form.username.trim(), password: form.password })
    afterLogin()
  } catch (error) {
    errorMessage.value = describeError(error)
  } finally {
    loading.value = false
  }
}

// ---------------------------------------------------------------- 手机号登录

const smsFormRef = ref<FormInstance>()
const smsLoading = ref(false)
const codeSending = ref(false)
const smsErrorMessage = ref('')
const cooldown = ref(0)
const smsForm = reactive({ phone: '', code: '' })

const smsRules: FormRules<typeof smsForm> = {
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        callback(value && normalizePhone(String(value)) ? undefined : new Error('请输入 11 位内地手机号'))
      },
      trigger: 'blur'
    }
  ],
  code: [
    { required: true, message: '请输入验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码是 6 位数字', trigger: 'blur' }
  ]
}

let cooldownTimer: ReturnType<typeof setInterval> | undefined

function startCooldown(seconds: number): void {
  cooldown.value = Math.max(1, Math.ceil(seconds))
  if (cooldownTimer) clearInterval(cooldownTimer)
  cooldownTimer = setInterval(() => {
    cooldown.value -= 1
    if (cooldown.value <= 0 && cooldownTimer) {
      clearInterval(cooldownTimer)
      cooldownTimer = undefined
    }
  }, 1000)
}

onBeforeUnmount(() => {
  if (cooldownTimer) clearInterval(cooldownTimer)
})

async function onSendCode(): Promise<void> {
  const phone = normalizePhone(smsForm.phone)
  if (!phone) {
    smsErrorMessage.value = '请先填写正确的 11 位手机号'
    return
  }
  smsForm.phone = phone
  smsErrorMessage.value = ''
  codeSending.value = true
  try {
    const result = await sendLoginCode(phone)
    startCooldown(result?.cooldownSeconds || 60)
    ElMessage.success('验证码已发出，若该手机号已绑定账号即可收到')
  } catch (error) {
    const seconds = cooldownSecondsOf(error)
    if (seconds !== null) {
      startCooldown(seconds)
      smsErrorMessage.value = `验证码发送过于频繁，请 ${seconds} 秒后再试`
    } else {
      smsErrorMessage.value = describeError(error)
    }
  } finally {
    codeSending.value = false
  }
}

async function onSmsSubmit(): Promise<void> {
  if (!smsFormRef.value) return
  try {
    await smsFormRef.value.validate()
  } catch {
    return
  }
  smsLoading.value = true
  smsErrorMessage.value = ''
  try {
    await auth.loginBySms({ phone: normalizePhone(smsForm.phone) ?? smsForm.phone.trim(), code: smsForm.code.trim() })
    afterLogin()
  } catch (error) {
    smsErrorMessage.value = describeError(error)
  } finally {
    smsLoading.value = false
  }
}

function afterLogin(): void {
  ElMessage.success('登录成功')
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
  void router.replace(redirect)
}
</script>

<template>
  <div class="login">
    <div class="login__card">
      <div class="login__brand">
        <el-icon class="login__brand-icon"><Promotion /></el-icon>
        <div>
          <p class="login__brand-title">微信第三方平台管理后台</p>
          <p class="login__brand-sub">小程序授权接入 · 代码发布 · 审核与灰度</p>
        </div>
      </div>

      <el-tabs v-model="mode" class="login__tabs" stretch>
        <el-tab-pane label="账号密码" name="password">
          <el-form ref="formRef" :model="form" :rules="rules" label-position="top" size="large" @submit.prevent="onSubmit">
            <el-form-item label="账号" prop="username">
              <el-input v-model="form.username" placeholder="请输入平台运营账号" autocomplete="username" clearable>
                <template #prefix>
                  <el-icon><User /></el-icon>
                </template>
              </el-input>
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input
                v-model="form.password"
                type="password"
                placeholder="请输入密码"
                autocomplete="current-password"
                show-password
                @keyup.enter="onSubmit"
              >
                <template #prefix>
                  <el-icon><Lock /></el-icon>
                </template>
              </el-input>
            </el-form-item>

            <el-alert
              v-if="errorMessage"
              :title="errorMessage"
              type="error"
              show-icon
              :closable="false"
              class="login__alert"
            />

            <el-button type="primary" class="login__submit" :loading="loading" @click="onSubmit">登录</el-button>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="手机号验证码" name="sms">
          <el-form
            ref="smsFormRef"
            :model="smsForm"
            :rules="smsRules"
            label-position="top"
            size="large"
            @submit.prevent="onSmsSubmit"
          >
            <el-form-item label="手机号" prop="phone">
              <el-input v-model="smsForm.phone" placeholder="11 位手机号" autocomplete="tel" clearable>
                <template #prefix>
                  <el-icon><Iphone /></el-icon>
                </template>
              </el-input>
            </el-form-item>
            <el-form-item label="验证码" prop="code">
              <div class="login__code">
                <el-input
                  v-model="smsForm.code"
                  placeholder="6 位验证码"
                  maxlength="6"
                  inputmode="numeric"
                  autocomplete="one-time-code"
                  @keyup.enter="onSmsSubmit"
                >
                  <template #prefix>
                    <el-icon><Message /></el-icon>
                  </template>
                </el-input>
                <el-button class="login__code-btn" :disabled="cooldown > 0" :loading="codeSending" @click="onSendCode">
                  {{ cooldown > 0 ? `${cooldown} 秒后重发` : '获取验证码' }}
                </el-button>
              </div>
            </el-form-item>

            <el-alert
              v-if="smsErrorMessage"
              :title="smsErrorMessage"
              type="error"
              show-icon
              :closable="false"
              class="login__alert"
            />

            <el-button type="primary" class="login__submit" :loading="smsLoading" @click="onSmsSubmit">登录</el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>

      <p class="login__tip">
        仅限平台运营人员访问。若登录态失效，页面会自动跳回本页面。<br />
        手机号登录需先在后台右上角「手机号登录」里绑定，未绑定的号码收不到可用验证码。
      </p>
    </div>
  </div>
</template>

<style scoped>
.login {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(140deg, #16233c 0%, #1e3f6b 55%, #2b6cb0 100%);
  padding: 24px;
}

.login__card {
  width: 420px;
  max-width: 100%;
  background: #fff;
  border-radius: 14px;
  padding: 28px 30px 24px;
  box-shadow: 0 18px 45px rgba(15, 30, 55, 0.25);
}

.login__brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 22px;
}

.login__brand-icon {
  font-size: 26px;
  color: var(--el-color-primary);
  background: #eef4ff;
  border-radius: 10px;
  padding: 8px;
}

.login__brand-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.login__brand-sub {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--app-text-muted);
}

.login__tabs {
  margin-bottom: 4px;
}

.login__tabs :deep(.el-tabs__header) {
  margin-bottom: 18px;
}

.login__code {
  display: flex;
  gap: 10px;
  width: 100%;
}

.login__code .el-input {
  flex: 1;
  min-width: 0;
}

.login__code-btn {
  width: 116px;
  flex-shrink: 0;
}

.login__alert {
  margin-bottom: 14px;
}

.login__submit {
  width: 100%;
}

.login__tip {
  margin: 16px 0 0;
  font-size: 12px;
  color: var(--app-text-muted);
  text-align: center;
  line-height: 1.6;
}
</style>
