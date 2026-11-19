<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Tools } from '@element-plus/icons-vue'

import type { AuthorizerItem } from '@/api/authorizer'
import { AuthorizerSelect, EmptyState, PageHeader, StatusTag } from '@/components'
import BasicInfoTab from '@/views/authorizer/tabs/BasicInfoTab.vue'
import CategoryTab from '@/views/authorizer/tabs/CategoryTab.vue'
import DomainTab from '@/views/authorizer/tabs/DomainTab.vue'
import PrivacyTab from '@/views/authorizer/tabs/PrivacyTab.vue'
import TesterTab from '@/views/authorizer/tabs/TesterTab.vue'
import { displayValue } from '@/utils/format'

type Scope = 'basicInfo' | 'categoriesDomains' | 'privacy' | 'testers'
type TabKey = 'basic' | 'category' | 'domain' | 'privacy' | 'tester'

const props = defineProps<{ scope: Scope }>()

const route = useRoute()
const router = useRouter()

const APPID_STORAGE_KEY = 'wx_platform_last_appid'

const appid = ref<string>(typeof route.query.appid === 'string' ? route.query.appid : localStorage.getItem(APPID_STORAGE_KEY) ?? '')
const selected = ref<AuthorizerItem | null>(null)
const activeTab = ref<TabKey>('basic')

const scopeMeta: Record<Scope, { title: string; subtitle: string; tabs: Array<{ key: TabKey; label: string }> }> = {
  basicInfo: {
    title: '基本信息',
    subtitle: '修改小程序名称、功能简介与头像（受微信侧修改次数限制）',
    tabs: [{ key: 'basic', label: '名称 / 简介 / 头像' }]
  },
  categoriesDomains: {
    title: '类目与域名',
    subtitle: '服务类目决定审核结论，服务器域名与业务域名决定小程序能否访问你们的接口与 H5',
    tabs: [
      { key: 'category', label: '服务类目' },
      { key: 'domain', label: '服务器域名 / 业务域名' }
    ]
  },
  privacy: {
    title: '用户隐私保护指引',
    subtitle: '声明小程序收集了哪些用户信息与用途，与代码不一致或缺失会被审核驳回',
    tabs: [{ key: 'privacy', label: '收集的信息与收集方信息' }]
  },
  testers: {
    title: '体验成员',
    subtitle: '维护可访问体验版的微信号',
    tabs: [{ key: 'tester', label: '体验成员' }]
  }
}

const meta = computed(() => scopeMeta[props.scope])

watch(
  () => props.scope,
  () => {
    activeTab.value = scopeMeta[props.scope].tabs[0]?.key ?? 'basic'
  },
  { immediate: true }
)

watch(appid, (value) => {
  if (value) {
    localStorage.setItem(APPID_STORAGE_KEY, value)
    void router.replace({ path: route.path, query: { ...route.query, appid: value } })
  }
})

function onSelectChange(item: AuthorizerItem | null): void {
  selected.value = item
}
</script>

<template>
  <div class="page">
    <div class="page__inner">
      <PageHeader :title="meta.title" :subtitle="meta.subtitle">
        <template #icon>
          <el-icon><Tools /></el-icon>
        </template>
        <template #extra>
          <AuthorizerSelect v-model="appid" :clearable="false" @change="onSelectChange" />
        </template>
      </PageHeader>

      <section v-if="selected" class="panel scope-summary">
        <div class="flex-row">
          <el-avatar :size="30" :src="selected.headImg ?? undefined">{{ (selected.nickName || selected.appid).slice(0, 1) }}</el-avatar>
          <span class="strong">{{ selected.nickName || '未命名小程序' }}</span>
          <span class="mono muted">{{ selected.appid }}</span>
          <StatusTag kind="authState" :value="selected.authState" />
          <span class="muted">线上版本 {{ displayValue(selected.releaseVersion, '未发布') }}</span>
        </div>
      </section>

      <section v-if="!appid" class="panel">
        <EmptyState
          description="请先在右上角选择一个已接入的小程序"
          sub-description="类目、域名、隐私保护指引、体验成员都属于单个小程序的配置。尚未接入的话请先去「扫码授权」生成二维码。"
        />
      </section>

      <template v-else>
        <section v-if="meta.tabs.length > 1" class="panel scope-tabs">
          <el-tabs v-model="activeTab">
            <el-tab-pane v-for="tab in meta.tabs" :key="tab.key" :label="tab.label" :name="tab.key" />
          </el-tabs>
        </section>

        <section class="panel">
          <BasicInfoTab v-if="activeTab === 'basic'" :key="`basic-${appid}`" :appid="appid" />
          <CategoryTab v-else-if="activeTab === 'category'" :key="`category-${appid}`" :appid="appid" />
          <DomainTab v-else-if="activeTab === 'domain'" :key="`domain-${appid}`" :appid="appid" />
          <PrivacyTab v-else-if="activeTab === 'privacy'" :key="`privacy-${appid}`" :appid="appid" />
          <TesterTab v-else-if="activeTab === 'tester'" :key="`tester-${appid}`" :appid="appid" />
        </section>
      </template>
    </div>
  </div>
</template>

<style scoped>
.scope-summary {
  margin-bottom: 14px;
}

.scope-tabs {
  margin-bottom: 14px;
  padding-top: 6px;
}

.scope-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}
</style>
