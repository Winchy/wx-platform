<script lang="ts">
export default { name: 'DraftListView' }
</script>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Picture, Refresh, SetUp } from '@element-plus/icons-vue'

import { draftToTemplate, listDrafts, syncDrafts, type DraftItem } from '@/api/release'
import { EmptyState, FormDialog, PageHeader } from '@/components'
import { displayValue, formatDateTime } from '@/utils/format'
import { describeError } from '@/utils/request'

const loading = ref(false)
const syncing = ref(false)
const errorText = ref('')
const rows = ref<DraftItem[]>([])

const convertVisible = ref(false)
const convertRow = ref<DraftItem | null>(null)
const convertLoading = ref(false)
/** 微信 addtotemplate 的 template_type：0 普通模板（默认）/ 1 标准模板库 */
const templateType = ref(0)

const templateTypeOptions = [
  { value: 0, label: '普通模板' },
  { value: 1, label: '标准模板库' }
]

function screenshotList(row: DraftItem): string[] {
  const value = row.screenshot
  if (!value) return []
  if (Array.isArray(value)) return value as string[]
  return [value]
}

/** el-table 插槽只提供松类型的 row，这里用下标回到强类型数组 */
function rowAt(index: number): DraftItem {
  return rows.value[index]
}

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    const data = await listDrafts()
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
    const data = await syncDrafts()
    rows.value = Array.isArray(data) ? data : []
    ElMessage.success(`已同步 ${rows.value.length} 个草稿`)
  } catch (err) {
    errorText.value = describeError(err)
  } finally {
    syncing.value = false
  }
}

function onCreateTemplate(row: DraftItem): void {
  convertRow.value = row
  templateType.value = 0
  convertVisible.value = true
}

async function submitConvert(): Promise<void> {
  const row = convertRow.value
  if (!row) return
  convertLoading.value = true
  try {
    await draftToTemplate(row.draftId, templateType.value)
    ElMessage.success(`草稿 ${row.draftId} 已转为代码模板，可同步模板后用于上传代码`)
    convertVisible.value = false
    await load()
  } catch (err) {
    ElMessage.error(describeError(err))
  } finally {
    convertLoading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page__inner">
      <PageHeader title="草稿箱" subtitle="来自微信开发者工具上传的草稿，需先转为代码模板才能上传到体验版">
        <template #extra>
          <el-button type="primary" :loading="syncing" @click="onSync">
            <el-icon><Refresh /></el-icon>
            <span>同步草稿</span>
          </el-button>
        </template>
      </PageHeader>

      <el-alert v-if="errorText" type="error" show-icon :closable="false" :title="errorText" class="mb-12" />

      <section class="panel">
        <el-table v-loading="loading" :data="rows" border :empty-text="loading ? '加载中…' : '暂无草稿'">
          <el-table-column label="截图" width="104">
            <template #default="{ $index }: { $index: number }">
              <el-image
                v-if="screenshotList(rowAt($index)).length"
                :src="screenshotList(rowAt($index))[0]"
                :preview-src-list="screenshotList(rowAt($index))"
                :initial-index="0"
                preview-teleported
                fit="cover"
                class="draft-shot"
              >
                <template #error>
                  <div class="draft-shot__error">
                    <el-icon><Picture /></el-icon>
                  </div>
                </template>
              </el-image>
              <div v-else class="draft-shot draft-shot__empty">
                <el-icon><Picture /></el-icon>
                <span>无截图</span>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="草稿 ID" width="150">
            <template #default="{ $index }: { $index: number }">
              <span class="mono">{{ rowAt($index).draftId }}</span>
            </template>
          </el-table-column>

          <el-table-column label="版本号" width="120">
            <template #default="{ $index }: { $index: number }">
              {{ displayValue(rowAt($index).userVersion, '未填写') }}
            </template>
          </el-table-column>

          <el-table-column label="描述" min-width="240" show-overflow-tooltip>
            <template #default="{ $index }: { $index: number }">
              {{ displayValue(rowAt($index).userDesc, '未填写描述') }}
            </template>
          </el-table-column>

          <el-table-column label="创建时间" width="180">
            <template #default="{ $index }: { $index: number }">
              {{ formatDateTime(rowAt($index).createdTime) }}
            </template>
          </el-table-column>

          <el-table-column label="同步时间" width="180">
            <template #default="{ $index }: { $index: number }">
              {{ formatDateTime(rowAt($index).syncedAt) }}
            </template>
          </el-table-column>

          <el-table-column label="操作" width="140" align="right" fixed="right">
            <template #default="{ $index }: { $index: number }">
              <el-button
                text
                size="small"
                type="primary"
                :icon="SetUp"
                @click="onCreateTemplate(rowAt($index))"
              >
                转为模板
              </el-button>
            </template>
          </el-table-column>

          <template #empty>
            <EmptyState
              description="草稿箱为空"
              sub-description="在微信开发者工具中「上传代码 → 选择草稿箱」即可产生草稿，然后回到这里点击同步。"
            >
              <el-button type="primary" :loading="syncing" @click="onSync">同步草稿</el-button>
            </EmptyState>
          </template>
        </el-table>

        <p class="muted draft-tip">
          提示：草稿是开放平台账号级资源，与具体小程序无关；微信侧没有删除草稿的接口，只能把它转为代码模板后使用。
        </p>
      </section>

      <FormDialog
        v-model="convertVisible"
        title="草稿转为代码模板"
        confirm-text="转为模板"
        width="420px"
        :loading="convertLoading"
        @confirm="submitConvert"
        @cancel="convertRow = null"
      >
        <p v-if="convertRow" class="muted draft-tip">
          草稿 {{ convertRow.draftId }}（{{ displayValue(convertRow.userVersion, '未填写版本号') }}）
        </p>
        <el-form label-width="90px">
          <el-form-item label="模板类型">
            <el-select v-model="templateType" style="width: 100%">
              <el-option
                v-for="item in templateTypeOptions"
                :key="item.value"
                :value="item.value"
                :label="item.label"
              />
            </el-select>
          </el-form-item>
        </el-form>
        <p class="muted draft-tip">转换成功后会同步模板列表，可直接在发布工作台选择该模板上传代码。</p>
      </FormDialog>
    </div>
  </div>
</template>

<style scoped>
.mb-12 {
  margin-bottom: 12px;
}

.draft-shot {
  width: 76px;
  height: 76px;
  border-radius: 6px;
  border: 1px solid var(--app-border-color);
  background: #f7f8fa;
}

.draft-shot__error,
.draft-shot__empty {
  width: 76px;
  height: 76px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border-radius: 6px;
  border: 1px dashed var(--app-border-color);
  color: var(--app-text-muted);
  font-size: 11px;
}

.draft-action {
  display: inline-block;
}

.draft-tip {
  margin: 12px 0 0;
  font-size: 12px;
  line-height: 1.6;
}
</style>
