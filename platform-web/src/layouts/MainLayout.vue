<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  ArrowDown,
  Bell,
  Connection,
  Document,
  Expand,
  Fold,
  Histogram,
  Iphone,
  Link,
  List,
  Lock,
  Management,
  Odometer,
  Promotion,
  Setting,
  SwitchButton,
  Tools,
  User
} from '@element-plus/icons-vue'

import { bindPhone, changePassword, sendBindCode, unbindPhone } from '@/api/auth'
import { FormDialog } from '@/components'
import { useAuthStore } from '@/stores/auth'
import { cooldownSecondsOf, describeError } from '@/utils/request'
import { normalizePhone } from '@/utils/phone'

interface MenuEntry {
  path: string
  title: string
  icon: Component
}

interface MenuGroup {
  title: string
  icon: Component
  children: MenuEntry[]
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const collapsed = ref(false)

const menuGroups: MenuGroup[] = [
  {
    title: '概览',
    icon: Odometer,
    children: [{ path: '/dashboard', title: '运营概览', icon: Odometer }]
  },
  {
    title: '接入管理',
    icon: Link,
    children: [
      { path: '/authorizers', title: '小程序列表', icon: Connection },
      { path: '/authorizers/new', title: '扫码授权', icon: User }
    ]
  },
  {
    title: '研发发布',
    icon: Promotion,
    children: [
      { path: '/release', title: '发布工作台', icon: Promotion },
      { path: '/templates', title: '代码模板', icon: Document },
      { path: '/drafts', title: '草稿箱', icon: List }
    ]
  },
  {
    title: '配置',
    icon: Setting,
    children: [
      { path: '/config/categories-domains', title: '类目与域名', icon: Management },
      { path: '/config/privacy', title: '隐私保护指引', icon: Lock },
      { path: '/config/basic-info', title: '基本信息', icon: Tools },
      { path: '/config/testers', title: '体验成员', icon: User }
    ]
  },
  {
    title: '运维',
    icon: Histogram,
    children: [
      { path: '/events', title: '事件推送', icon: Bell },
      { path: '/logs', title: '操作日志', icon: Histogram },
      { path: '/settings/platform', title: '平台配置', icon: Setting }
    ]
  }
]

/** 详情页等高亮映射：/authorizers/:appid 归到小程序列表 */
const activeMenu = computed(() => {
  const path = route.path
  if (path.startsWith('/authorizers/') && path !== '/authorizers/new') return '/authorizers'
  return path
})

const breadcrumb = computed(() => {
  const items: Array<{ title: string; path?: string }> = []
  const group = route.meta.group as string | undefined
  if (group) items.push({ title: group })
  const parent = route.meta.parent as { title: string; path: string } | undefined
  if (parent) items.push({ ...parent })
  const title = route.meta.title as string | undefined
  if (title && title !== group) items.push({ title })
  return items
})

const displayName = computed(() => auth.user?.nickname || auth.user?.username || '未登录')
const roleLabel = computed(() => {
  const role = (auth.user?.role ?? '').toUpperCase()
  if (role === 'ADMIN') return '管理员'
  if (role === 'OPERATOR') return '运营'
  return auth.user?.role || '成员'
})

async function onLogout(): Promise<void> {
  try {
    await ElMessageBox.confirm('确认退出登录？退出后需要重新输入账号密码。', '退出登录', {
      type: 'warning',
      confirmButtonText: '退出',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  await auth.logout()
  void router.push({ name: 'login' })
}

const pwdVisible = ref(false)
const pwdSubmitting = ref(false)
const pwdFormRef = ref<FormInstance>()
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

const pwdRules: FormRules<typeof pwdForm> = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, max: 64, message: '新密码长度需为 8-64 位', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        callback(value && value === pwdForm.oldPassword
          ? new Error('新密码不能与原密码相同')
          : undefined)
      },
      trigger: 'blur'
    }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        callback(value && value === pwdForm.newPassword ? undefined : new Error('两次输入的新密码不一致'))
      },
      trigger: 'blur'
    }
  ]
}

function openPasswordDialog(): void {
  pwdForm.oldPassword = ''
  pwdForm.newPassword = ''
  pwdForm.confirmPassword = ''
  pwdVisible.value = true
}

async function onSubmitPassword(): Promise<void> {
  const form = pwdFormRef.value
  if (form) {
    try {
      await form.validate()
    } catch {
      return
    }
  }
  pwdSubmitting.value = true
  try {
    await changePassword({ oldPassword: pwdForm.oldPassword, newPassword: pwdForm.newPassword })
    pwdVisible.value = false
    // 改密把当前令牌一起作废了，只能本地清会话；再调 /logout 是用一枚已失效的令牌
    auth.reset()
    ElMessage.success('密码已修改，请用新密码重新登录')
    void router.push({ name: 'login' })
  } catch {
    // 原密码不正确 / 长度不够 / 与原密码相同，都由请求层统一提示，这里不再重复弹一次
  } finally {
    pwdSubmitting.value = false
  }
}

const phoneVisible = ref(false)
const phoneSubmitting = ref(false)
const unbinding = ref(false)
const codeSending = ref(false)
const phoneCooldown = ref(0)
const phoneError = ref('')
const phoneFormRef = ref<FormInstance>()
const phoneForm = reactive({ phone: '', code: '', password: '' })

const boundPhone = computed(() => auth.user?.phoneMasked ?? '')

const phoneRules: FormRules<typeof phoneForm> = {
  phone: [
    { required: true, message: '请输入要绑定的手机号', trigger: 'blur' },
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
  ],
  password: [{ required: true, message: '请输入当前账号密码', trigger: 'blur' }]
}

let phoneTimer: ReturnType<typeof setInterval> | undefined

function startPhoneCooldown(seconds: number): void {
  phoneCooldown.value = Math.max(1, Math.ceil(seconds))
  if (phoneTimer) clearInterval(phoneTimer)
  phoneTimer = setInterval(() => {
    phoneCooldown.value -= 1
    if (phoneCooldown.value <= 0 && phoneTimer) {
      clearInterval(phoneTimer)
      phoneTimer = undefined
    }
  }, 1000)
}

onBeforeUnmount(() => {
  if (phoneTimer) clearInterval(phoneTimer)
})

function openPhoneDialog(): void {
  phoneForm.phone = ''
  phoneForm.code = ''
  phoneForm.password = ''
  phoneError.value = ''
  phoneVisible.value = true
}

async function onSendBindCode(): Promise<void> {
  const phone = normalizePhone(phoneForm.phone)
  if (!phone) {
    phoneError.value = '请先填写正确的手机号'
    return
  }
  phoneForm.phone = phone
  phoneError.value = ''
  codeSending.value = true
  try {
    const result = await sendBindCode(phone)
    startPhoneCooldown(result?.cooldownSeconds || 60)
    ElMessage.success('验证码已发送')
  } catch (error) {
    const seconds = cooldownSecondsOf(error)
    if (seconds !== null) {
      startPhoneCooldown(seconds)
      phoneError.value = `验证码发送过于频繁，请 ${seconds} 秒后再试`
    } else {
      phoneError.value = describeError(error)
    }
  } finally {
    codeSending.value = false
  }
}

async function onSubmitBind(): Promise<void> {
  const form = phoneFormRef.value
  if (form) {
    try {
      await form.validate()
    } catch {
      return
    }
  }
  const phone = normalizePhone(phoneForm.phone)
  if (!phone) {
    phoneError.value = '请输入 11 位内地手机号'
    return
  }
  phoneSubmitting.value = true
  phoneError.value = ''
  try {
    await bindPhone({ phone, code: phoneForm.code.trim(), password: phoneForm.password })
    phoneVisible.value = false
    // 绑定不动 pwd_ver，当前令牌依旧有效，只要把脱敏号重新拉回来回显
    await auth.loadUser()
    ElMessage.success('手机号已绑定，下次可用验证码登录')
  } catch (error) {
    // 原密码不正确 / 验证码错 / 号码已被别的账号占用（409）：接口是 silent 的，只落在弹窗里
    phoneError.value = describeError(error)
  } finally {
    phoneSubmitting.value = false
  }
}

async function onUnbind(): Promise<void> {
  if (!phoneForm.password) {
    phoneError.value = '解绑需要输入当前账号密码'
    return
  }
  unbinding.value = true
  phoneError.value = ''
  try {
    await unbindPhone(phoneForm.password)
    phoneVisible.value = false
    await auth.loadUser()
    ElMessage.success('手机号已解绑，该号码不能再用于验证码登录')
  } catch (error) {
    phoneError.value = describeError(error)
  } finally {
    unbinding.value = false
  }
}

function onCommand(command: string): void {
  if (command === 'logout') {
    void onLogout()
  } else if (command === 'password') {
    openPasswordDialog()
  } else if (command === 'phone') {
    openPhoneDialog()
  } else if (command === 'settings') {
    void router.push('/settings/platform')
  }
}
</script>

<template>
  <el-container class="layout">
    <el-aside class="layout__aside" :width="collapsed ? '64px' : '216px'">
      <div class="layout__brand">
        <el-icon class="layout__brand-icon"><Promotion /></el-icon>
        <span v-if="!collapsed" class="layout__brand-text">第三方平台后台</span>
      </div>
      <el-scrollbar class="layout__menu-scroll">
        <el-menu
          :default-active="activeMenu"
          :collapse="collapsed"
          :collapse-transition="false"
          router
          class="layout__menu"
        >
          <template v-for="group in menuGroups" :key="group.title">
            <el-menu-item v-if="group.children.length === 1" :index="group.children[0]?.path">
              <el-icon><component :is="group.children[0]?.icon" /></el-icon>
              <template #title>{{ group.children[0]?.title }}</template>
            </el-menu-item>
            <el-sub-menu v-else :index="group.title">
              <template #title>
                <el-icon><component :is="group.icon" /></el-icon>
                <span>{{ group.title }}</span>
              </template>
              <el-menu-item v-for="item in group.children" :key="item.path" :index="item.path">
                <el-icon><component :is="item.icon" /></el-icon>
                <template #title>{{ item.title }}</template>
              </el-menu-item>
            </el-sub-menu>
          </template>
        </el-menu>
      </el-scrollbar>
    </el-aside>

    <el-container>
      <el-header class="layout__header" height="56px">
        <div class="layout__header-left">
          <el-button text circle :title="collapsed ? '展开菜单' : '收起菜单'" @click="collapsed = !collapsed">
            <el-icon :size="18">
              <Expand v-if="collapsed" />
              <Fold v-else />
            </el-icon>
          </el-button>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item v-for="item in breadcrumb" :key="item.title">
              <router-link v-if="item.path" :to="item.path">{{ item.title }}</router-link>
              <span v-else>{{ item.title }}</span>
            </el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="layout__header-right">
          <el-dropdown trigger="click" @command="onCommand">
            <span class="layout__user">
              <el-avatar :size="28" class="layout__avatar">{{ displayName.slice(0, 1) }}</el-avatar>
              <span class="layout__user-name">{{ displayName }}</span>
              <el-tag size="small" type="info" effect="plain">{{ roleLabel }}</el-tag>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="password" :icon="Lock">修改密码</el-dropdown-item>
                <el-dropdown-item command="phone" :icon="Iphone">手机号登录</el-dropdown-item>
                <el-dropdown-item command="settings" :icon="Setting">平台配置</el-dropdown-item>
                <el-dropdown-item divided command="logout" :icon="SwitchButton">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="layout__main">
        <router-view v-slot="{ Component }">
          <keep-alive :include="['AuthorizerListView', 'TemplateListView', 'DraftListView', 'EventListView', 'LogListView']">
            <component :is="Component" />
          </keep-alive>
        </router-view>
      </el-main>
    </el-container>

    <FormDialog
      v-model="pwdVisible"
      title="修改密码"
      width="440px"
      confirm-text="保存新密码"
      :loading="pwdSubmitting"
      @confirm="onSubmitPassword"
    >
      <el-alert
        class="layout__pwd-alert"
        type="warning"
        :closable="false"
        show-icon
        title="保存后当前登录立即失效，需要用新密码重新登录。"
        description="改密码不会解绑手机号，所以也可以用「手机号 + 验证码」重新登录。"
      />
      <el-form ref="pwdFormRef" :model="pwdForm" :rules="pwdRules" label-width="92px" @submit.prevent>
        <el-form-item label="原密码" prop="oldPassword">
          <el-input
            v-model="pwdForm.oldPassword"
            type="password"
            show-password
            autocomplete="current-password"
            placeholder="请输入当前密码"
          />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input
            v-model="pwdForm.newPassword"
            type="password"
            show-password
            autocomplete="new-password"
            placeholder="8-64 位，且不能与原密码相同"
          />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input
            v-model="pwdForm.confirmPassword"
            type="password"
            show-password
            autocomplete="new-password"
            placeholder="请再次输入新密码"
            @keyup.enter="onSubmitPassword"
          />
        </el-form-item>
      </el-form>
    </FormDialog>

    <FormDialog
      v-model="phoneVisible"
      title="手机号登录"
      width="460px"
      confirm-text="保存绑定"
      :loading="phoneSubmitting"
      @confirm="onSubmitBind"
    >
      <el-alert
        class="layout__pwd-alert"
        :type="boundPhone ? 'success' : 'info'"
        :closable="false"
        show-icon
        :title="boundPhone ? `当前已绑定 ${boundPhone}` : '尚未绑定手机号'"
        :description="boundPhone
          ? '填新号码 + 验证码可换绑；只想停用手机号登录，点下方「解绑手机号」。'
          : '绑定后即可用手机号 + 验证码登录，不必每次输密码。'"
      />
      <el-form ref="phoneFormRef" :model="phoneForm" :rules="phoneRules" label-width="92px" @submit.prevent>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="phoneForm.phone" placeholder="11 位手机号" autocomplete="tel" clearable />
        </el-form-item>
        <el-form-item label="验证码" prop="code">
          <div class="layout__phone-code">
            <el-input
              v-model="phoneForm.code"
              placeholder="6 位验证码"
              maxlength="6"
              inputmode="numeric"
              autocomplete="one-time-code"
            />
            <el-button
              class="layout__phone-code-btn"
              :disabled="phoneCooldown > 0"
              :loading="codeSending"
              @click="onSendBindCode"
            >
              {{ phoneCooldown > 0 ? `${phoneCooldown} 秒后重发` : '获取验证码' }}
            </el-button>
          </div>
        </el-form-item>
        <el-form-item label="当前密码" prop="password">
          <el-input
            v-model="phoneForm.password"
            type="password"
            show-password
            autocomplete="current-password"
            placeholder="绑定与解绑都要验证当前密码"
          />
        </el-form-item>
      </el-form>
      <el-alert v-if="phoneError" class="layout__pwd-alert" type="error" :closable="false" show-icon :title="phoneError" />
      <p class="layout__phone-note">一个手机号只能绑一个账号；验证码 5 分钟内有效，同一号码 60 秒只能发一次。</p>
      <template #footer-left>
        <el-button v-if="boundPhone" text type="danger" :disabled="phoneSubmitting" :loading="unbinding" @click="onUnbind">
          解绑手机号
        </el-button>
      </template>
    </FormDialog>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
}

.layout__aside {
  background: #111c2e;
  color: #d5dbe7;
  transition: width 0.2s ease;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.layout__brand {
  height: var(--app-header-height);
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 16px;
  flex-shrink: 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.layout__brand-icon {
  font-size: 19px;
  color: #4d8dff;
}

.layout__brand-text {
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
}

.layout__menu-scroll {
  flex: 1;
  min-height: 0;
}

.layout__menu {
  border-right: none;
  background: transparent;
}

.layout__menu:not(.el-menu--collapse) {
  width: 216px;
}

.layout__menu :deep(.el-menu-item),
.layout__menu :deep(.el-sub-menu__title) {
  color: #c3cad9;
  height: 44px;
  line-height: 44px;
}

.layout__menu :deep(.el-menu--inline) {
  background: #0d1728;
}

.layout__menu :deep(.el-menu-item:hover),
.layout__menu :deep(.el-sub-menu__title:hover) {
  background: #1b293f;
  color: #ffffff;
}

.layout__menu :deep(.el-menu-item.is-active) {
  background: #1e3f6b;
  color: #ffffff;
  border-right: 2px solid #4d8dff;
}

.layout__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid var(--app-border-color);
  padding: 0 16px;
}

.layout__header-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.layout__header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.layout__user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
  padding: 4px 6px;
  border-radius: 6px;
}

.layout__user:hover {
  background: #f3f5f9;
}

.layout__avatar {
  background: var(--el-color-primary);
  color: #fff;
}

.layout__user-name {
  font-size: 13px;
  color: #374151;
}

.layout__main {
  background: var(--app-bg);
  padding: 0;
  overflow: auto;
}

.layout__pwd-alert {
  margin-bottom: 16px;
}

.layout__phone-code {
  display: flex;
  gap: 10px;
  width: 100%;
}

.layout__phone-code .el-input {
  flex: 1;
  min-width: 0;
}

.layout__phone-code-btn {
  width: 116px;
  flex-shrink: 0;
}

.layout__phone-note {
  margin: 0;
  font-size: 12px;
  color: var(--app-text-muted);
  line-height: 1.6;
}
</style>
