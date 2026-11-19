<script lang="ts">
export default { name: 'TemplateListView' }
</script>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Promotion, Refresh, Search } from '@element-plus/icons-vue'

import { deleteTemplate, listTemplates, syncTemplates, type CodeTemplate } from '@/api/release'
import { EmptyState, PageHeader } from '@/components'
import { copyToClipboard, displayValue, formatDateTime, formatRelativeTime } from '@/utils/format'
import { describeError } from '@/utils/request'

const router = useRouter()

const loading = ref(false)
const syncing = ref(false)
const errorText = ref('')
const rows = ref<CodeTemplate[]>([])
const keyword = ref('')
const deletingId = ref<string | number | ''>('')

const visibleRows = computed<CodeTemplate[]>(() => {
  const key = keyword.value.trim().toLowerCase()
  if (!key) return rows.value
  return rows.value.filter((item) =>
    [String(item.templateId), item.userVersion ?? '', item.userDesc ?? '', String(item.draftId ?? '')]
      .join(' ')
      .toLowerCase()
      .includes(key)
  )
})

/** el-table 插槽只提供松类型的 row，这里用下标回到强类型数组 */
function rowAt(index: number): CodeTemplate {
  return visibleRows.value[index]
}

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    const data = await listTemplates()
    rows.value = Array.isArray(data) ? data : []
  } catch (err) {
    errorText.value = describeError(err)
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function onSync(): Promise<void> {
  syncing.value = true
  errorText.value = ''
  try {
    const data = await syncTemplates()
    rows.value = Array.isArray(data) ? data : []
    ElMessage.success(`已同步 ${rows.value.length} 个代码模板`)
  } catch (err) {
    errorText.value = describeError(err)
  } finally {
    syncing.value = false
  }
}

async function onDelete(row: CodeTemplate): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `删除模板 ${row.templateId} 后，微信开放平台该模板将不可再用，历史发布记录不受影响。`,
      '删除代码模板',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  deletingId.value = row.templateId
  try {
    await deleteTemplate(row.templateId)
    ElMessage.success('模板已删除')
    await load()
  } catch (err) {
    ElMessage.error(describeError(err))
  } finally {
    deletingId.value = ''
  }
}

async function copyId(row: CodeTemplate): Promise<void> {
  const ok = await copyToClipboard(String(row.templateId))
  ElMessage[ok ? 'success' : 'error'](ok ? '模板 ID 已复制' : '复制失败')
}

function useInWorkbench(row: CodeTemplate): void {
  void router.push({ path: '/release', query: { templateId: String(row.templateId) } })
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page__inner">
      <PageHeader title="代码模板库" subtitle="开放平台「小程序代码模板」的标准模板列表，用于批量给各协会小程序上传代码">
        <template #extra>
          <el-button :loading="loading" text circle title="重新加载" @click="load">
            <el-icon><Refresh /></el-icon>
          </el-button>
          <el-button type="primary" :loading="syncing" @click="onSync">
            <el-icon><Refresh /></el-icon>
            <span>同步模板</span>
          </el-button>
        </template>
      </PageHeader>

      <section class="panel">
        <div class="filter-bar">
          <el-input v-model="keyword" placeholder="筛选模板 ID / 版本号 / 描述" clearable style="width: 280px">
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <span class="muted">共 {{ visibleRows.length }} 条{{ keyword ? `（列表总数 ${rows.length}）` : '' }}</span>
          <div class="filter-bar__right">
            <el-button text type="primary" @click="router.push('/drafts')">从草稿生成模板</el-button>
          </div>
        </div>

        <el-alert v-if="errorText" type="error" show-icon :closable="false" :title="errorText" class="mb-12" />

        <el-table v-loading="loading" :data="visibleRows" border :empty-text="loading ? '加载中…' : '暂无模板'">
          <el-table-column label="模板 ID" width="140">
            <template #default="{ $index }: { $index: number }">
              <span class="mono strong">{{ rowAt($index).templateId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="版本号" width="120">
            <template #default="{ $index }: { $index: number }">
              {{ displayValue(rowAt($index).userVersion, '未填写') }}
            </template>
          </el-table-column>
          <el-table-column label="模板描述" min-width="260" show-overflow-tooltip>
            <template #default="{ $index }: { $index: number }">
              {{ displayValue(rowAt($index).userDesc, '未填写描述') }}
            </template>
          </el-table-column>
          <el-table-column label="来源草稿" width="140">
            <template #default="{ $index }: { $index: number }">
              <span class="mono">{{ displayValue(rowAt($index).draftId, '—') }}</span>
            </template>
          </el-table-column>
          <el-table-column label="同步时间" width="200">
            <template #default="{ $index }: { $index: number }">
              <div class="flex-col" style="gap: 0">
                <span>{{ formatDateTime(rowAt($index).syncedAt) }}</span>
                <span class="muted">{{ formatRelativeTime(rowAt($index).syncedAt) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="260" align="right" fixed="right">
            <template #default="{ $index }: { $index: number }">
              <el-button text size="small" @click="copyId(rowAt($index))">复制 ID</el-button>
              <el-button text size="small" type="primary" :icon="Promotion" @click="useInWorkbench(rowAt($index))">
                去发布
              </el-button>
              <el-button
                text
                size="small"
                type="danger"
                :icon="Delete"
                :loading="deletingId === rowAt($index).templateId"
                @click="onDelete(rowAt($index))"
              >
                删除
              </el-button>
            </template>
          </el-table-column>

          <template #empty>
            <EmptyState
              description="模板列表为空"
              sub-description="请先在微信开放平台创建代码模板，然后点击右上「同步模板」拉取到本平台。"
            >
              <el-button type="primary" :loading="syncing" @click="onSync">立即同步</el-button>
            </EmptyState>
          </template>
        </el-table>

        <p class="muted template-tip">
          提示：模板 ID 是开放平台账号级资源，所有已授权小程序共用；发布工作台按模板 + ext.json 差异化下发到各协会。
        </p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.mb-12 {
  margin-bottom: 12px;
}

.template-tip {
  margin: 12px 0 0;
  font-size: 12px;
  line-height: 1.6;
}
</style>
