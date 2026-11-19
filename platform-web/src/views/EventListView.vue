<script lang="ts">
export default { name: 'EventListView' }
</script>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { Refresh, Search } from '@element-plus/icons-vue'

import { getEventDetail, listEvents, type EventDetail, type EventItem, type EventQueryParams } from '@/api/log'
import { EmptyState, JsonBlock, PageHeader, StatusTag } from '@/components'
import { displayValue, formatDateTime, formatRelativeTime } from '@/utils/format'
import { infoTypeOptions } from '@/utils/status'
import { describeError } from '@/utils/request'

const loading = ref(false)
const errorText = ref('')
const rows = ref<EventItem[]>([])
const total = ref(0)

const query = reactive<Required<EventQueryParams>>({
  infoType: '',
  appid: '',
  page: 1,
  size: 20
})

/** 列表接口不返回报文体，展开某一行时才按 ID 取详情 */
const details = ref<Record<string, EventDetail | null>>({})
const detailErrors = ref<Record<string, string>>({})
const detailLoadingId = ref('')

function detailKey(id: number | string): string {
  return String(id)
}

function detailOf(id: number | string): EventDetail | null {
  return details.value[detailKey(id)] ?? null
}

async function loadDetail(id: number | string): Promise<void> {
  const key = detailKey(id)
  if (key in details.value) return
  detailLoadingId.value = key
  try {
    details.value[key] = await getEventDetail(id)
  } catch (err) {
    detailErrors.value[key] = describeError(err)
    details.value[key] = null
  } finally {
    detailLoadingId.value = ''
  }
}

function onExpandChange(row: EventItem, expanded: EventItem[] | boolean): void {
  const opened = Array.isArray(expanded)
    ? expanded.some((item) => detailKey(item.id) === detailKey(row.id))
    : expanded
  if (opened) void loadDetail(row.id)
}

/** el-table 插槽只提供松类型的 row，这里用下标回到强类型数组 */
function rowAt(index: number): EventItem {
  return rows.value[index]
}

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  details.value = {}
  detailErrors.value = {}
  try {
    const page = await listEvents({
      infoType: query.infoType || undefined,
      appid: query.appid.trim() || undefined,
      page: query.page,
      size: query.size
    })
    rows.value = page?.records ?? []
    total.value = page?.total ?? 0
  } catch (err) {
    errorText.value = describeError(err)
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function onSearch(): void {
  query.page = 1
  void load()
}

function onReset(): void {
  query.infoType = ''
  query.appid = ''
  query.page = 1
  void load()
}

function yesNo(value: boolean | number | null | undefined): { label: string; ok: boolean } {
  const ok = value === true || Number(value) === 1
  return { label: ok ? '是' : '否', ok }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page__inner">
      <PageHeader
        title="事件推送"
        subtitle="微信开放平台回调到本服务器的第三方事件（票据、授权变更等），用于排查授权与发布异常"
      >
        <template #extra>
          <el-button :loading="loading" @click="load">
            <el-icon><Refresh /></el-icon>
            <span>刷新</span>
          </el-button>
        </template>
      </PageHeader>

      <section class="panel">
        <div class="filter-bar">
          <el-select v-model="query.infoType" placeholder="事件类型" style="width: 180px" clearable @change="onSearch">
            <el-option v-for="item in infoTypeOptions" :key="item.value" :value="item.value" :label="item.label" />
          </el-select>
          <el-input
            v-model="query.appid"
            placeholder="按小程序 AppID 过滤"
            clearable
            style="width: 260px"
            @keyup.enter="onSearch"
            @clear="onSearch"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <div class="filter-bar__right">
            <el-button @click="onReset">重置</el-button>
            <el-button type="primary" :loading="loading" @click="onSearch">查询</el-button>
          </div>
        </div>

        <el-alert v-if="errorText" type="error" show-icon :closable="false" :title="errorText" class="mb-12" />

        <el-table
          v-loading="loading"
          :data="rows"
          border
          :empty-text="loading ? '加载中…' : '暂无事件'"
          @expand-change="onExpandChange"
        >
          <el-table-column type="expand">
            <template #default="{ $index }: { $index: number }">
              <div v-loading="detailLoadingId === detailKey(rowAt($index).id)" class="event-detail">
                <div v-if="detailOf(rowAt($index).id)" class="grid-two-col">
                  <JsonBlock
                    label="解密后报文 decryptedBody"
                    :value="detailOf(rowAt($index).id)?.decryptedBody"
                    :max-height="300"
                  />
                  <JsonBlock
                    label="原始报文 rawBody"
                    :value="detailOf(rowAt($index).id)?.rawBody"
                    :format-json="false"
                    :max-height="300"
                  />
                </div>
                <p v-else-if="detailErrors[detailKey(rowAt($index).id)]" class="event-detail__error">
                  报文加载失败：{{ detailErrors[detailKey(rowAt($index).id)] }}
                </p>
                <p v-else class="muted">展开后从 GET /api/events/&#123;id&#125; 读取完整报文…</p>
                <p v-if="rowAt($index).errorMessage" class="event-detail__error">处理异常：{{ rowAt($index).errorMessage }}</p>
                <p class="muted event-detail__meta">
                  记录 ID {{ rowAt($index).id }}　·　报文类型 {{ displayValue(rowAt($index).bodyType, '未知') }}　·　接收时间
                  {{ formatDateTime(rowAt($index).receivedAt) }}
                </p>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="事件类型" width="140">
            <template #default="{ $index }: { $index: number }">
              <StatusTag kind="infoType" :value="rowAt($index).infoType" />
            </template>
          </el-table-column>

          <el-table-column label="小程序 AppID" width="200">
            <template #default="{ $index }: { $index: number }">
              <span class="mono">{{ displayValue(rowAt($index).appid, '平台级事件') }}</span>
            </template>
          </el-table-column>

          <el-table-column label="报文" min-width="160">
            <template #default="{ $index }: { $index: number }">
              <span class="muted">{{ displayValue(rowAt($index).bodyType, '—') }}</span>
            </template>
          </el-table-column>

          <el-table-column label="签名" width="90">
            <template #default="{ $index }: { $index: number }">
              <el-tag :type="yesNo(rowAt($index).signatureOk).ok ? 'success' : 'danger'" size="small" effect="plain">
                {{ yesNo(rowAt($index).signatureOk).label }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="处理" width="90">
            <template #default="{ $index }: { $index: number }">
              <el-tag :type="yesNo(rowAt($index).handled).ok ? 'success' : 'warning'" size="small" effect="plain">
                {{ yesNo(rowAt($index).handled).label }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="异常" min-width="160" show-overflow-tooltip>
            <template #default="{ $index }: { $index: number }">
              {{ displayValue(rowAt($index).errorMessage, '—') }}
            </template>
          </el-table-column>

          <el-table-column label="接收时间" width="180">
            <template #default="{ $index }: { $index: number }">
              <div class="flex-col" style="gap: 0">
                <span>{{ formatDateTime(rowAt($index).receivedAt) }}</span>
                <span class="muted">{{ formatRelativeTime(rowAt($index).receivedAt) }}</span>
              </div>
            </template>
          </el-table-column>

          <template #empty>
            <EmptyState
              description="暂无事件记录"
              sub-description="请确认开放平台「授权事件接收 URL」指向本服务的 /wx/callback 且票据推送正常。"
            />
          </template>
        </el-table>

        <div class="pagination-bar">
          <el-pagination
            v-model:current-page="query.page"
            v-model:page-size="query.size"
            :total="total"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next, jumper"
            background
            @size-change="onSearch"
            @current-change="load"
          />
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.mb-12 {
  margin-bottom: 12px;
}

.event-detail {
  padding: 12px 18px 14px;
  background: #fbfcfd;
}

.event-detail__error {
  margin: 10px 0 0;
  color: var(--el-color-danger);
  font-size: 12px;
}

.event-detail__meta {
  margin: 8px 0 0;
  font-size: 12px;
}
</style>
