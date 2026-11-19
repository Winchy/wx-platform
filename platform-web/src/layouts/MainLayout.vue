<script setup lang="ts">
import { computed, ref, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  ArrowDown,
  Bell,
  Connection,
  Document,
  Expand,
  Fold,
  Histogram,
  Link,
  List,
  Lock,
  Management,
  Odometer,
  Promotion,
  Setting,
  Tools,
  User
} from '@element-plus/icons-vue'

import { useAuthStore } from '@/stores/auth'

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

function onCommand(command: string): void {
  if (command === 'logout') {
    void onLogout()
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
                <el-dropdown-item command="settings">平台配置</el-dropdown-item>
                <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
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
</style>
