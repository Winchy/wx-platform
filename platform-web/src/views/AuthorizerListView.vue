<script lang="ts">
export default { name: 'AuthorizerListView' }
</script>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Promotion, Refresh, Search, User } from '@element-plus/icons-vue'

import {
  listAuthorizers,
  refreshAuthorizerInfo,
  revokeAuthorizer,
  type AuthorizerItem,
  type AuthorizerQueryParams
} from '@/api/authorizer'
import { EmptyState, PageHeader, StatusTag } from '@/components'
import { displayValue, formatDateTime, formatRelativeTime } from '@/utils/format'
import { authStateOptions } from '@/utils/status'
import { describeError } from '@/utils/request'

const router = useRouter()

const loading = ref(false)
const rows = ref<AuthorizerItem[]>([])
const total = ref(0)
const errorMessage = ref('')

const query = reactive<Required<AuthorizerQueryParams>>({
  keyword: '',
  authState: '',
  page: 1,
  size: 20
})

const refreshingAppid = ref('')
const revokingAppid = ref('')
/** el-table 插槽只提供松类型的 row，这里用下标回到强类型数组 */
function rowAt(index: number): AuthorizerItem {
  return rows.value[index]
}


async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const page = await listAuthorizers({
      keyword: query.keyword.trim() || undefined,
      authState: query.authState || undefined,
      page: query.page,
      size: query.size
    })
    rows.value = page?.records ?? []
    total.value = page?.total ?? 0
  } catch (error) {
    errorMessage.value = describeError(error)
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
  query.keyword = ''
  query.authState = ''
  query.page = 1
  void load()
}

function openWorkbench(row: AuthorizerItem): void {
  void router.push({ path: '/release', query: { appid: row.appid } })
}

function openDetail(row: AuthorizerItem): void {
  void router.push(`/authorizers/${row.appid}`)
}

async function onRefreshInfo(row: AuthorizerItem): Promise<void> {
  refreshingAppid.value = row.appid
  try {
    const latest = await refreshAuthorizerInfo(row.appid)
    const index = rows.value.findIndex((item) => item.appid === row.appid)
    if (index >= 0) {
      rows.value.splice(index, 1, { ...rows.value[index], ...latest })
    }
    ElMessage.success('已从微信侧刷新小程序信息')
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    refreshingAppid.value = ''
  }
}

async function onRevoke(row: AuthorizerItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `将移除「${row.nickName || row.appid}」在本平台的授权记录，小程序开发者需要在开放平台重新扫码授权。`,
      '解除授权',
      { type: 'warning', confirmButtonText: '确认解除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  revokingAppid.value = row.appid
  try {
    await revokeAuthorizer(row.appid)
    ElMessage.success('已解除本地授权记录')
    await load()
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    revokingAppid.value = ''
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page__inner">
      <PageHeader title="小程序列表" subtitle="通过扫码授权接入到第三方平台的协会/商会小程序">
        <template #extra>
          <el-button @click="load" :loading="loading">
            <el-icon><Refresh /></el-icon>
            <span>刷新列表</span>
          </el-button>
          <el-button type="primary" @click="router.push('/authorizers/new')">
            <el-icon><User /></el-icon>
            <span>扫码接入</span>
          </el-button>
        </template>
      </PageHeader>

      <section class="panel">
        <div class="filter-bar">
          <el-input
            v-model="query.keyword"
            placeholder="搜索小程序名称 / AppID / 主体"
            clearable
            style="width: 280px"
            @keyup.enter="onSearch"
            @clear="onSearch"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-select v-model="query.authState" placeholder="授权状态" style="width: 160px" clearable @change="onSearch">
            <el-option v-for="item in authStateOptions" :key="item.value" :value="item.value" :label="item.label" />
          </el-select>
          <div class="filter-bar__right">
            <el-button @click="onReset">重置</el-button>
            <el-button type="primary" :loading="loading" @click="onSearch">查询</el-button>
          </div>
        </div>

        <el-alert v-if="errorMessage" type="error" show-icon :closable="false" :title="errorMessage" class="mb-12" />

        <el-table
          v-loading="loading"
          :data="rows"
          row-key="appid"
          border
          class="authorizer-table"
          :empty-text="loading ? '加载中…' : '暂无数据'"
        >
          <template #empty>
            <EmptyState
              description="还没有接入的小程序"
              sub-description="点击右上角「扫码接入」，让协会/商会的小程序管理员扫码授权后即可在此管理发布流程。"
            >
              <el-button type="primary" @click="router.push('/authorizers/new')">生成授权二维码</el-button>
            </EmptyState>
          </template>

          <el-table-column label="小程序" min-width="240">
            <template #default="{ $index }: { $index: number }">
              <div class="cell-app">
                <el-avatar :size="34" :src="rowAt($index).headImg ?? undefined">
                  {{ (rowAt($index).nickName || rowAt($index).appid).slice(0, 1) }}
                </el-avatar>
                <div class="cell-app__text">
                  <span class="strong ellipsis">{{ rowAt($index).nickName || '未命名小程序' }}</span>
                  <span class="mono muted">{{ rowAt($index).appid }}</span>
                </div>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="主体 / 简称" min-width="180">
            <template #default="{ $index }: { $index: number }">
              <div class="flex-col" style="gap: 2px">
                <span class="ellipsis">{{ displayValue(rowAt($index).principalName, '主体信息未返回') }}</span>
                <span class="muted ellipsis">{{ displayValue(rowAt($index).alias, '无简称') }}</span>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="认证状态" width="110">
            <template #default="{ $index }: { $index: number }">
              <StatusTag kind="verifyType" :value="rowAt($index).verifyType" empty-text="未知" />
            </template>
          </el-table-column>

          <el-table-column label="授权状态" width="120">
            <template #default="{ $index }: { $index: number }">
              <StatusTag kind="authState" :value="rowAt($index).authState" />
            </template>
          </el-table-column>

          <el-table-column label="线上版本" width="110">
            <template #default="{ $index }: { $index: number }">
              <span class="mono">{{ displayValue(rowAt($index).releaseVersion, '未发布') }}</span>
            </template>
          </el-table-column>

          <el-table-column label="授权时间" width="180">
            <template #default="{ $index }: { $index: number }">
              <div class="flex-col" style="gap: 0">
                <span>{{ formatDateTime(rowAt($index).authorizedAt, '—') }}</span>
                <span class="muted">{{ formatRelativeTime(rowAt($index).tokenExpiredAt) }} 令牌到期</span>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="250" fixed="right">
            <template #default="{ $index }: { $index: number }">
              <div class="row-actions">
                <el-button text type="primary" size="small" @click="openWorkbench(rowAt($index))">
                  <el-icon><Promotion /></el-icon>
                  <span>工作台</span>
                </el-button>
                <el-button text size="small" @click="openDetail(rowAt($index))">详情</el-button>
                <el-button
                  text
                  size="small"
                  :loading="refreshingAppid === rowAt($index).appid"
                  @click="onRefreshInfo(rowAt($index))"
                >
                  刷新信息
                </el-button>
                <el-button
                  text
                  size="small"
                  type="danger"
                  :loading="revokingAppid === rowAt($index).appid"
                  @click="onRevoke(rowAt($index))"
                >
                  <el-icon><Delete /></el-icon>
                  <span>解除授权</span>
                </el-button>
              </div>
            </template>
          </el-table-column>
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

.cell-app {
  display: flex;
  align-items: center;
  gap: 10px;
}

.cell-app__text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.row-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 2px;
}

.row-actions .el-button + .el-button {
  margin-left: 0;
}
</style>
