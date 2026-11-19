<script lang="ts">
export default { name: 'LogListView' }
</script>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { Refresh, Search, View } from '@element-plus/icons-vue'

import { listOperationLogs, type OperationLogItem, type OperationLogQueryParams } from '@/api/log'
import { EmptyState, JsonBlock, PageHeader } from '@/components'
import { displayValue, formatDateTime, formatMillis, formatRelativeTime, prettyJson } from '@/utils/format'
import { describeError } from '@/utils/request'

const loading = ref(false)
const errorText = ref('')
const rows = ref<OperationLogItem[]>([])
const total = ref(0)

const query = reactive<{ action: string; appid: string; success: string; page: number; size: number }>({
  action: '',
  appid: '',
  success: '',
  page: 1,
  size: 20
})

const drawerVisible = ref(false)
const current = ref<OperationLogItem | null>(null)

const successOptions = [
  { value: '', label: '全部结果' },
  { value: 'true', label: '仅成功' },
  { value: 'false', label: '仅失败' }
]

/** el-table 插槽只提供松类型的 row，这里用下标回到强类型数组 */
function rowAt(index: number): OperationLogItem {
  return rows.value[index]
}

function buildParams(): OperationLogQueryParams {
  const params: OperationLogQueryParams = { page: query.page, size: query.size }
  if (query.action.trim()) params.action = query.action.trim()
  if (query.appid.trim()) params.appid = query.appid.trim()
  if (query.success) params.success = query.success === 'true'
  return params
}

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    const page = await listOperationLogs(buildParams())
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
  query.action = ''
  query.appid = ''
  query.success = ''
  query.page = 1
  void load()
}

function openDetail(row: OperationLogItem): void {
  current.value = row
  drawerVisible.value = true
}

const detailSummary = () => {
  const row = current.value
  if (!row) return ''
  return prettyJson(
    JSON.stringify({
      operator: row.operatorName,
      action: row.action,
      authorizerAppid: row.authorizerAppid,
      apiPath: row.apiPath,
      success: row.success,
      errcode: row.errcode,
      errmsg: row.errmsg,
      costMs: row.costMs,
      createdAt: row.createdAt
    })
  )
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page__inner">
      <PageHeader title="操作日志" subtitle="记录后台发起的微信接口调用（谁、做了什么、耗时、成功与否），用于审计与排障">
        <template #extra>
          <el-button :loading="loading" @click="load">
            <el-icon><Refresh /></el-icon>
            <span>刷新</span>
          </el-button>
        </template>
      </PageHeader>

      <section class="panel">
        <div class="filter-bar">
          <el-input
            v-model="query.action"
            placeholder="操作名（精确匹配），如 commit / submit_audit / release"
            clearable
            style="width: 260px"
            @keyup.enter="onSearch"
            @clear="onSearch"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-input
            v-model="query.appid"
            placeholder="小程序 AppID"
            clearable
            style="width: 220px"
            @keyup.enter="onSearch"
            @clear="onSearch"
          />
          <el-select v-model="query.success" placeholder="执行结果" style="width: 140px" @change="onSearch">
            <el-option v-for="item in successOptions" :key="item.value" :value="item.value" :label="item.label" />
          </el-select>
          <div class="filter-bar__right">
            <el-button @click="onReset">重置</el-button>
            <el-button type="primary" :loading="loading" @click="onSearch">查询</el-button>
          </div>
        </div>

        <el-alert v-if="errorText" type="error" show-icon :closable="false" :title="errorText" class="mb-12" />

        <el-table v-loading="loading" :data="rows" border :empty-text="loading ? '加载中…' : '暂无日志'">
          <el-table-column label="时间" width="180">
            <template #default="{ $index }: { $index: number }">
              <div class="flex-col" style="gap: 0">
                <span>{{ formatDateTime(rowAt($index).createdAt) }}</span>
                <span class="muted">{{ formatRelativeTime(rowAt($index).createdAt) }}</span>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="操作人" width="130">
            <template #default="{ $index }: { $index: number }">
              {{ displayValue(rowAt($index).operatorName, '系统') }}
            </template>
          </el-table-column>

          <el-table-column label="动作" min-width="160">
            <template #default="{ $index }: { $index: number }">
              <span class="strong">{{ displayValue(rowAt($index).action, '—') }}</span>
            </template>
          </el-table-column>

          <el-table-column label="小程序" width="190">
            <template #default="{ $index }: { $index: number }">
              <span class="mono">{{ displayValue(rowAt($index).authorizerAppid, '—') }}</span>
            </template>
          </el-table-column>

          <el-table-column label="接口路径" min-width="240" show-overflow-tooltip>
            <template #default="{ $index }: { $index: number }">
              <span class="mono muted">{{ displayValue(rowAt($index).apiPath, '—') }}</span>
            </template>
          </el-table-column>

          <el-table-column label="结果" width="150">
            <template #default="{ $index }: { $index: number }">
              <div class="flex-row">
                <el-tag :type="rowAt($index).success ? 'success' : 'danger'" size="small" effect="plain">
                  {{ rowAt($index).success ? '成功' : '失败' }}
                </el-tag>
                <span v-if="!rowAt($index).success" class="mono muted">{{ displayValue(rowAt($index).errcode, '—') }}</span>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="耗时" width="100" align="right">
            <template #default="{ $index }: { $index: number }">
              {{ formatMillis(rowAt($index).costMs) }}
            </template>
          </el-table-column>

          <el-table-column label="操作" width="90" align="right" fixed="right">
            <template #default="{ $index }: { $index: number }">
              <el-button text size="small" type="primary" :icon="View" @click="openDetail(rowAt($index))">详情</el-button>
            </template>
          </el-table-column>

          <template #empty>
            <EmptyState description="暂无操作日志" sub-description="在发布工作台或配置页执行操作后，这里会留下审计记录。" />
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

      <el-drawer v-model="drawerVisible" title="日志详情" size="520px">
        <template v-if="current">
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="记录 ID">{{ displayValue(current.id) }}</el-descriptions-item>
            <el-descriptions-item label="操作人">{{ displayValue(current.operatorName, '系统') }}</el-descriptions-item>
            <el-descriptions-item label="动作">{{ displayValue(current.action) }}</el-descriptions-item>
            <el-descriptions-item label="小程序">{{ displayValue(current.authorizerAppid) }}</el-descriptions-item>
            <el-descriptions-item label="结果">
              <el-tag :type="current.success ? 'success' : 'danger'" size="small" effect="plain">
                {{ current.success ? '成功' : '失败' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="errcode">{{ displayValue(current.errcode) }}</el-descriptions-item>
            <el-descriptions-item label="errmsg">{{ displayValue(current.errmsg) }}</el-descriptions-item>
            <el-descriptions-item label="耗时">{{ formatMillis(current.costMs) }}</el-descriptions-item>
            <el-descriptions-item label="时间">{{ formatDateTime(current.createdAt) }}</el-descriptions-item>
          </el-descriptions>

          <JsonBlock class="log-drawer__block" label="请求摘要（apiPath）" :value="current.apiPath" :format-json="false" />
          <JsonBlock class="log-drawer__block" label="响应摘要（结果字段）" :value="detailSummary()" :max-height="320" />

          <p class="muted log-drawer__tip">
            后端契约未单列 request body / response body 字段，这里由已返回的 apiPath、errcode、errmsg 组装摘要。
          </p>
        </template>
      </el-drawer>
    </div>
  </div>
</template>

<style scoped>
.mb-12 {
  margin-bottom: 12px;
}

.log-drawer__block {
  margin-top: 16px;
}

.log-drawer__tip {
  margin: 14px 0 0;
  font-size: 12px;
  line-height: 1.6;
}
</style>
