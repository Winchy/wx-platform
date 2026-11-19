import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: '概览', group: '概览' }
      },
      {
        path: 'authorizers',
        name: 'authorizer-list',
        component: () => import('@/views/AuthorizerListView.vue'),
        meta: { title: '小程序列表', group: '接入管理' }
      },
      {
        path: 'authorizers/new',
        name: 'authorizer-authorize',
        component: () => import('@/views/AuthorizeApplyView.vue'),
        meta: { title: '扫码授权', group: '接入管理' }
      },
      {
        path: 'authorizers/:appid',
        name: 'authorizer-detail',
        component: () => import('@/views/AuthorizerDetailView.vue'),
        props: true,
        meta: { title: '小程序详情', group: '接入管理', parent: { title: '小程序列表', path: '/authorizers' } }
      },
      {
        path: 'release',
        name: 'release-workbench',
        component: () => import('@/views/ReleaseWorkbenchView.vue'),
        meta: { title: '发布工作台', group: '研发发布' }
      },
      {
        path: 'templates',
        name: 'templates',
        component: () => import('@/views/TemplateListView.vue'),
        meta: { title: '代码模板', group: '研发发布' }
      },
      {
        path: 'drafts',
        name: 'drafts',
        component: () => import('@/views/DraftListView.vue'),
        meta: { title: '草稿箱', group: '研发发布' }
      },
      {
        path: 'config/basic-info',
        name: 'config-basic-info',
        component: () => import('@/views/config/ConfigScopePage.vue'),
        props: { scope: 'basicInfo' },
        meta: { title: '基本信息', group: '配置' }
      },
      {
        path: 'config/categories-domains',
        name: 'config-categories-domains',
        component: () => import('@/views/config/ConfigScopePage.vue'),
        props: { scope: 'categoriesDomains' },
        meta: { title: '类目与域名', group: '配置' }
      },
      {
        path: 'config/privacy',
        name: 'config-privacy',
        component: () => import('@/views/config/ConfigScopePage.vue'),
        props: { scope: 'privacy' },
        meta: { title: '隐私保护指引', group: '配置' }
      },
      {
        path: 'config/testers',
        name: 'config-testers',
        component: () => import('@/views/config/ConfigScopePage.vue'),
        props: { scope: 'testers' },
        meta: { title: '体验成员', group: '配置' }
      },
      {
        path: 'events',
        name: 'events',
        component: () => import('@/views/EventListView.vue'),
        meta: { title: '事件推送', group: '运维' }
      },
      {
        path: 'logs',
        name: 'logs',
        component: () => import('@/views/LogListView.vue'),
        meta: { title: '操作日志', group: '运维' }
      },
      {
        path: 'settings/platform',
        name: 'settings-platform',
        component: () => import('@/views/PlatformSettingsView.vue'),
        meta: { title: '平台配置', group: '运维' }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { public: true, title: '页面不存在' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  if (to.meta.public) {
    if (to.name === 'login' && auth.isAuthenticated) {
      return { path: '/dashboard' }
    }
    return true
  }

  if (!auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  if (!auth.user) {
    const ok = await auth.loadUser()
    if (!ok) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
  }
  return true
})

router.afterEach((to) => {
  const title = (to.meta.title as string | undefined) ?? ''
  document.title = title ? `${title} · 微信第三方平台管理后台` : '微信第三方平台管理后台'
})

export default router
