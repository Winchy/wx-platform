<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Key, Promotion, Refresh } from '@element-plus/icons-vue'

import { getAuthorizer, refreshAuthorizerInfo, revokeAuthorizer, type AuthorizerItem } from '@/api/authorizer'
import { PageHeader, StatusTag } from '@/components'
import { displayValue, formatDateTime, formatRelativeTime } from '@/utils/format'
import { describeError } from '@/utils/request'

import BasicInfoTab from './authorizer/tabs/BasicInfoTab.vue'
import CategoryTab from './authorizer/tabs/CategoryTab.vue'
import DomainTab from './authorizer/tabs/DomainTab.vue'
import PrivacyTab from './authorizer/tabs/PrivacyTab.vue'
import ReleaseHistoryTab from './authorizer/tabs/ReleaseHistoryTab.vue'
import ReleaseStatusTab from './authorizer/tabs/ReleaseStatusTab.vue'
import TesterTab from './authorizer/tabs/TesterTab.vue'

const props = defineProps<{ appid: string }>()

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const errorText = ref('')
const detail = ref<AuthorizerItem | null>(null)
const activeTab = ref<string>(typeof route.query.tab === 'string' ? route.query.tab : 'basic')
const refreshing = ref(false)
const revoking = ref(false)

const title = computed(() => detail.value?.nickName || props.appid)

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    detail.value = await getAuthorizer(props.appid)
  } catch (err) {
    errorText.value = describeError(err)
    detail.value = null
  } finally {
    loading.value = false
  }
}

async function onRefreshInfo(): Promise<void> {
  refreshing.value = true
  try {
    detail.value = await refreshAuthorizerInfo(props.appid)
    ElMessage.success('已从微信侧刷新小程序信息')
  } catch (err) {
    ElMessage.error(describeError(err))
  } finally {
    refreshing.value = false
  }
}

async function onRevoke(): Promise<void> {
  try {
    await ElMessageBox.confirm('确认解除该小程序在本平台的授权记录？', '解除授权', {
      type: 'warning',
      confirmButtonText: '解除授权',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  revoking.value = true
  try {
    await revokeAuthorizer(props.appid)
    ElMessage.success('已解除授权')
    void router.push('/authorizers')
  } catch (err) {
    ElMessage.error(describeError(err))
  } finally {
    revoking.value = false
  }
}

function onTabChange(name: string | number): void {
  activeTab.value = String(name)
  void router.replace({ path: route.path, query: { ...route.query, tab: activeTab.value } })
}

watch(() => props.appid, load)
onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page__inner" v-loading="loading">
      <PageHeader :title="title" :subtitle="`AppID ${props.appid}`">
        <template #extra>
          <el-button text :icon="ArrowLeft" @click="router.push('/authorizers')">返回列表</el-button>
          <el-button :loading="refreshing" :icon="Refresh" @click="onRefreshInfo">刷新信息</el-button>
          <el-button
            type="primary"
            :icon="Promotion"
            @click="router.push({ path: '/release', query: { appid: props.appid } })"
          >
            发布工作台
          </el-button>
          <el-button :icon="Key" @click="router.push({ path: '/authorizers/new', query: { appid: props.appid } })">
            重新授权
          </el-button>
          <el-button type="danger" plain :loading="revoking" @click="onRevoke">解除授权</el-button>
        </template>
      </PageHeader>

      <el-alert v-if="errorText" type="error" show-icon :closable="false" :title="errorText" class="mb-14" />

      <section v-if="detail" class="panel detail-summary">
        <div class="detail-summary__avatar">
          <el-avatar :size="52" :src="detail.headImg ?? undefined">
            {{ (detail.nickName || detail.appid).slice(0, 1) }}
          </el-avatar>
        </div>
        <el-descriptions :column="4" size="small" class="detail-summary__desc">
          <el-descriptions-item label="授权状态">
            <StatusTag kind="authState" :value="detail.authState" />
          </el-descriptions-item>
          <el-descriptions-item label="线上版本">{{ displayValue(detail.releaseVersion, '未发布') }}</el-descriptions-item>
          <el-descriptions-item label="认证状态">
            <StatusTag kind="verifyType" :value="detail.verifyType" empty-text="未知" />
          </el-descriptions-item>
          <el-descriptions-item label="主体">{{ displayValue(detail.principalName) }}</el-descriptions-item>
          <el-descriptions-item label="简称">{{ displayValue(detail.alias) }}</el-descriptions-item>
          <el-descriptions-item label="服务类型">{{ displayValue(detail.serviceType) }}</el-descriptions-item>
          <el-descriptions-item label="授权时间">{{ formatDateTime(detail.authorizedAt) }}</el-descriptions-item>
          <el-descriptions-item label="令牌到期">
            {{ formatDateTime(detail.tokenExpiredAt) }}
            <span class="muted">（{{ formatRelativeTime(detail.tokenExpiredAt) }}）</span>
          </el-descriptions-item>
        </el-descriptions>
      </section>

      <section class="panel detail-tabs">
        <el-tabs v-model="activeTab" @tab-change="onTabChange">
          <el-tab-pane label="基本信息" name="basic">
            <BasicInfoTab v-if="activeTab === 'basic'" :appid="props.appid" />
          </el-tab-pane>
          <el-tab-pane label="发布状态" name="release">
            <ReleaseStatusTab v-if="activeTab === 'release'" :appid="props.appid" />
          </el-tab-pane>
          <el-tab-pane label="类目" name="category">
            <CategoryTab v-if="activeTab === 'category'" :appid="props.appid" />
          </el-tab-pane>
          <el-tab-pane label="域名" name="domain">
            <DomainTab v-if="activeTab === 'domain'" :appid="props.appid" />
          </el-tab-pane>
          <el-tab-pane label="隐私指引" name="privacy">
            <PrivacyTab v-if="activeTab === 'privacy'" :appid="props.appid" />
          </el-tab-pane>
          <el-tab-pane label="体验成员" name="tester">
            <TesterTab v-if="activeTab === 'tester'" :appid="props.appid" />
          </el-tab-pane>
          <el-tab-pane label="发布历史" name="history">
            <ReleaseHistoryTab v-if="activeTab === 'history'" :appid="props.appid" @changed="load" />
          </el-tab-pane>
        </el-tabs>
      </section>
    </div>
  </div>
</template>

<style scoped>
.mb-14 {
  margin-bottom: 14px;
}

.detail-summary {
  display: flex;
  gap: 16px;
  align-items: center;
  margin-bottom: 14px;
}

.detail-summary__desc {
  flex: 1;
  min-width: 0;
}

.detail-tabs :deep(.el-tabs__header) {
  margin-bottom: 16px;
}
</style>
