<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowRight,
  CircleCheck,
  CircleClose,
  Clock,
  Connection,
  Promotion,
  Refresh,
  Setting,
  Tickets,
  Warning
} from '@element-plus/icons-vue'
import type { Component } from 'vue'

import { getPlatformConfig, type PlatformConfig } from '@/api/platform'
import { listEvents, listOperationLogs, type EventItem, type OperationLogItem } from '@/api/log'
import { PageHeader, StatusTag } from '@/components'
import { usePlatformHealthStore } from '@/stores/platformHealth'
import { displayValue, formatDurationSeconds, formatRelativeTime } from '@/utils/format'
import { describeError } from '@/utils/request'

const router = useRouter()
const healthStore = usePlatformHealthStore()

const config = ref<PlatformConfig | null>(null)
const configError = ref('')
const events = ref<EventItem[]>([])
const logs = ref<OperationLogItem[]>([])
const listLoading = ref(false)

interface MetricCard {
  key: string
  title: string
  icon: Component
  tone: 'success' | 'warning' | 'danger' | 'info'
  value: string
  hint: string
}

const metrics = computed<MetricCard[]>(() => {
  const health = healthStore.health
  return [
    {
      key: 'config',
      title: '第三方平台配置',
      icon: healthStore.configReady ? CircleCheck : CircleClose,
      tone: healthStore.configReady ? 'success' : 'danger',
      value: healthStore.configReady ? '已就绪' : '未就绪',
      hint: '需要 AppID、AppSecret、消息校验 Token、EncodingAESKey 全部配置完成'
    },
    {
      key: 'ticket',
      title: 'verify_ticket 票据',
      icon: healthStore.ticketFresh ? Tickets : Warning,
      tone: healthStore.ticketFresh ? 'success' : 'warning',
      value: healthStore.ticketFresh ? '12 分钟内已推送' : '票据不新鲜',
      hint: `距最近一次推送 ${formatDurationSeconds(health?.ticketAgeSeconds)}`
    },
    {
      key: 'token',
      title: 'component_access_token',
      icon: healthStore.componentTokenValid ? CircleCheck : CircleClose,
      tone: healthStore.componentTokenValid ? 'success' : 'danger',
      value: healthStore.componentTokenValid ? '有效' : '无效或已过期',
      hint: '令牌失效时所有代小程序接口都会失败，可在平台配置页重新校验'
    },
    {
      key: 'authorizers',
      title: '已接入小程序',
      icon: Connection,
      tone: 'info',
      value: `${health?.authorizedCount ?? 0} / ${health?.authorizerTotal ?? 0}`,
      hint: '已授权数 / 接入总数'
    }
  ]
})

/** el-table 插槽只提供松类型的 row，这里用下标回到强类型数组 */
function logAt(index: number): OperationLogItem {
  return logs.value[index]
}

function eventAt(index: number): EventItem {
  return events.value[index]
}

const ticketAgeText = computed(() => formatDurationSeconds(healthStore.health?.ticketAgeSeconds))
const healthErrorText = computed(() => healthStore.error || '')

async function loadSideData(): Promise<void> {
  listLoading.value = true
  try {
    const [eventPage, logPage] = await Promise.all([
      listEvents({ page: 1, size: 6 }).catch(() => null),
      listOperationLogs({ page: 1, size: 8 }).catch(() => null)
    ])
    events.value = eventPage?.records ?? []
    logs.value = logPage?.records ?? []
  } finally {
    listLoading.value = false
  }
}

async function loadConfig(): Promise<void> {
  configError.value = ''
  try {
    config.value = await getPlatformConfig()
  } catch (error) {
    configError.value = describeError(error)
  }
}

async function refreshAll(): Promise<void> {
  await Promise.all([healthStore.refresh(), loadConfig(), loadSideData()])
}

onMounted(() => {
  healthStore.start(20000)
  void refreshAll()
})

onBeforeUnmount(() => {
  healthStore.stop()
})

function goAppid(path: string): void {
  void router.push(path)
}
</script>

<template>
  <div class="page">
    <div class="page__inner">
      <PageHeader
        title="运营概览"
        subtitle="第三方平台运行状态、接入小程序与最近的审核发布动态"
      >
        <template #icon>
          <el-icon><Promotion /></el-icon>
        </template>
        <template #extra>
          <el-button :loading="healthStore.loading" @click="refreshAll">
            <el-icon><Refresh /></el-icon>
            <span>刷新</span>
          </el-button>
          <el-button type="primary" @click="goAppid('/authorizers/new')">
            <el-icon><Connection /></el-icon>
            <span>接入新小程序</span>
          </el-button>
        </template>
      </PageHeader>

      <el-alert
        v-if="healthErrorText"
        class="dash__alert"
        type="error"
        show-icon
        :closable="false"
        :title="`健康检查接口调用失败：${healthErrorText}`"
      />

      <div class="grid-metrics">
        <el-card v-for="item in metrics" :key="item.key" shadow="never" class="dash-card" :class="`dash-card--${item.tone}`">
          <div class="dash-card__head">
            <el-icon class="dash-card__icon"><component :is="item.icon" /></el-icon>
            <span class="dash-card__title">{{ item.title }}</span>
          </div>
          <p class="dash-card__value">{{ item.value }}</p>
          <p class="dash-card__hint">{{ item.hint }}</p>
        </el-card>
      </div>

      <div class="grid-two-col dash__row">
        <section class="panel">
          <h2 class="panel__title">
            <el-icon><Tickets /></el-icon>
            <span>平台凭证状态</span>
            <el-button class="panel__more" text type="primary" @click="goAppid('/settings/platform')">
              平台配置
            </el-button>
          </h2>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="开放平台 AppID">
              <span class="mono">{{ displayValue(config?.componentAppid) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="平台启用状态">
              <StatusTag kind="platformState" :value="config?.status" />
            </el-descriptions-item>
            <el-descriptions-item label="密钥配置">
              <div class="flex-row">
                <el-tag :type="config?.secretSet ? 'success' : 'info'" size="small" effect="plain">
                  AppSecret {{ config?.secretSet ? '已设置' : '未设置' }}
                </el-tag>
                <el-tag :type="config?.messageTokenSet ? 'success' : 'info'" size="small" effect="plain">
                  Token {{ config?.messageTokenSet ? '已设置' : '未设置' }}
                </el-tag>
                <el-tag :type="config?.aesKeySet ? 'success' : 'info'" size="small" effect="plain">
                  AESKey {{ config?.aesKeySet ? '已设置' : '未设置' }}
                </el-tag>
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="票据推送时间">
              {{ formatRelativeTime(config?.ticketReceivedAt) }}
            </el-descriptions-item>
            <el-descriptions-item label="令牌过期时间">
              {{ formatRelativeTime(config?.tokenExpiresAt) }}
            </el-descriptions-item>
            <el-descriptions-item label="票据距今">
              <div class="flex-row">
                <el-icon><Clock /></el-icon>
                <span>{{ ticketAgeText }}</span>
                <el-tag v-if="configError" type="warning" size="small">{{ configError }}</el-tag>
              </div>
            </el-descriptions-item>
          </el-descriptions>

          <div class="dash__quick">
            <span class="muted">快捷入口：</span>
            <el-button text type="primary" @click="goAppid('/release')">发布工作台</el-button>
            <el-button text type="primary" @click="goAppid('/templates')">代码模板</el-button>
            <el-button text type="primary" @click="goAppid('/drafts')">草稿箱</el-button>
            <el-button text type="primary" @click="goAppid('/events')">事件推送</el-button>
          </div>
        </section>

        <section class="panel">
          <h2 class="panel__title">
            <el-icon><Setting /></el-icon>
            <span>接入与发布动态</span>
            <el-button class="panel__more" text type="primary" @click="goAppid('/logs')">查看全部日志</el-button>
          </h2>
          <el-table v-loading="listLoading" :data="logs" size="small" :show-header="false" empty-text="暂无操作记录">
            <el-table-column type="index" width="34" />
            <el-table-column label="操作" min-width="220">
              <template #default="{ $index }: { $index: number }">
                <div class="flex-row">
                  <span class="strong">{{ displayValue(logAt($index).action, '未知操作') }}</span>
                  <span class="mono muted">{{ displayValue(logAt($index).authorizerAppid, '—') }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="结果" width="90">
              <template #default="{ $index }: { $index: number }">
                <el-tag :type="logAt($index).success ? 'success' : 'danger'" size="small" effect="plain">
                  {{ logAt($index).success ? '成功' : '失败' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="时间" width="150" align="right">
              <template #default="{ $index }: { $index: number }">
                <span class="muted">{{ formatRelativeTime(logAt($index).createdAt) }}</span>
              </template>
            </el-table-column>
          </el-table>

          <h3 class="dash__subtitle">最近事件推送</h3>
          <el-table v-loading="listLoading" :data="events" size="small" :show-header="false" empty-text="暂无事件">
            <el-table-column type="index" width="34" />
            <el-table-column label="类型" width="120">
              <template #default="{ $index }: { $index: number }">
                <StatusTag kind="infoType" :value="eventAt($index).infoType" />
              </template>
            </el-table-column>
            <el-table-column label="小程序" min-width="180">
              <template #default="{ $index }: { $index: number }">
                <span class="mono">{{ displayValue(eventAt($index).appid, '平台级事件') }}</span>
              </template>
            </el-table-column>
            <el-table-column label="处理" width="90">
              <template #default="{ $index }: { $index: number }">
                <el-tag :type="eventAt($index).handled ? 'success' : 'warning'" size="small" effect="plain">
                  {{ eventAt($index).handled ? '已处理' : '未处理' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="时间" width="140" align="right">
              <template #default="{ $index }: { $index: number }">
                <span class="muted">{{ formatRelativeTime(eventAt($index).receivedAt) }}</span>
              </template>
            </el-table-column>
          </el-table>

          <div class="dash__quick">
            <el-button text type="primary" @click="goAppid('/events')">
              <el-icon><ArrowRight /></el-icon>
              <span>查看完整事件列表</span>
            </el-button>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dash__alert {
  margin-bottom: 14px;
}

.dash__row {
  margin-top: 14px;
}

.panel__more {
  margin-left: auto;
}

.dash-card {
  border: 1px solid var(--app-border-color);
  border-radius: var(--app-radius);
}

.dash-card :deep(.el-card__body) {
  padding: 16px 18px;
}

.dash-card__head {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #4b5563;
  font-size: 13px;
}

.dash-card__icon {
  font-size: 16px;
}

.dash-card__value {
  margin: 12px 0 6px;
  font-size: 24px;
  font-weight: 600;
  color: #111827;
}

.dash-card__hint {
  margin: 0;
  font-size: 12px;
  color: var(--app-text-muted);
  line-height: 1.5;
  min-height: 34px;
}

.dash-card--success {
  border-left: 3px solid var(--el-color-success);
}

.dash-card--warning {
  border-left: 3px solid var(--el-color-warning);
}

.dash-card--danger {
  border-left: 3px solid var(--el-color-danger);
}

.dash-card--info {
  border-left: 3px solid var(--el-color-primary);
}

.dash__subtitle {
  margin: 18px 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: #4b5563;
}

.dash__quick {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 14px;
  font-size: 13px;
}
</style>
