<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Lock, Promotion, User } from '@element-plus/icons-vue'

import type { LoginParams } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { describeError } from '@/utils/request'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

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
    ElMessage.success('登录成功')
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
    await router.replace(redirect)
  } catch (error) {
    errorMessage.value = describeError(error)
  } finally {
    loading.value = false
  }
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

      <p class="login__tip">
        仅限平台运营人员访问。若登录态失效，页面会自动跳回本页面。
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
