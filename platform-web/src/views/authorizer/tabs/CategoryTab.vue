<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Plus, Refresh } from '@element-plus/icons-vue'

import {
  addCategory,
  listAllCategories,
  listCategories,
  removeCategory,
  type AuthorizerCategory,
  type CategoryTreeNode
} from '@/api/config'
import { EmptyState } from '@/components'
import {
  categoryAuditReason,
  categoryAuditText,
  categoryAuditType,
  categoryPathIds,
  categoryPathText,
  nodeAt,
  toCascaderOptions
} from '@/utils/category'
import { describeError } from '@/utils/request'

const props = defineProps<{ appid: string }>()

const loading = ref(false)
const errorText = ref('')
const categories = ref<AuthorizerCategory[]>([])
const quotaText = ref('')

const treeLoading = ref(false)
const treeError = ref('')
const tree = ref<CategoryTreeNode[]>([])

const adding = ref(false)
const form = reactive<{ path: number[] }>({ path: [] })

const cascaderOptions = computed(() => toCascaderOptions(tree.value))

/** 选中的最深一级：敏感类目要交哪些资质文件，微信在类目树里就给了 */
const selectedQualify = computed<string[]>(() => {
  const node = nodeAt(tree.value, form.path)
  return node && node.sensitiveType === 1 ? node.qualifyNames ?? [] : []
})

const cascaderProps = {
  value: 'value',
  label: 'label',
  children: 'children',
  expandTrigger: 'hover' as const
}

/** el-table 插槽只提供松类型的 row，这里用下标回到强类型数组 */
function rowAt(index: number): AuthorizerCategory {
  return categories.value[index]
}

async function loadCategories(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    const result = await listCategories(props.appid)
    categories.value = Array.isArray(result?.categories) ? result.categories : []
    const limit = Number(result?.categoryLimit ?? 0)
    const quota = Number(result?.quota ?? 0)
    quotaText.value = limit > 0 ? `已设置 ${categories.value.length} 个类目，最多 ${limit} 个（剩余可提交 ${quota} 个）` : ''
  } catch (err) {
    errorText.value = describeError(err)
    categories.value = []
    quotaText.value = ''
  } finally {
    loading.value = false
  }
}

async function loadTree(): Promise<void> {
  treeLoading.value = true
  treeError.value = ''
  try {
    tree.value = (await listAllCategories(props.appid)) ?? []
  } catch (err) {
    treeError.value = describeError(err)
    tree.value = []
  } finally {
    treeLoading.value = false
  }
}

async function refreshAll(): Promise<void> {
  await Promise.all([loadCategories(), loadTree()])
}

async function onAdd(): Promise<void> {
  if (form.path.length < 2) {
    ElMessage.warning('请至少选择一级与二级类目')
    return
  }
  const payload = {
    first: form.path[0] as number,
    second: form.path[1] as number,
    third: form.path[2]
  }
  adding.value = true
  try {
    await addCategory(props.appid, payload)
    ElMessage.success('类目已提交（部分类目需微信侧审核，稍后刷新查看）')
    form.path = []
    await refreshAll()
  } catch (err) {
    ElMessage.error(describeError(err))
  } finally {
    adding.value = false
  }
}

async function onRemove(category: AuthorizerCategory): Promise<void> {
  const first = category.firstId
  const second = category.secondId
  if (!first || !second) {
    ElMessage.warning('该类目缺少一二级编号，无法删除')
    return
  }
  try {
    await ElMessageBox.confirm(`确认删除类目「${categoryPathText(category)}」？`, '删除类目', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await removeCategory(props.appid, { first, second })
    ElMessage.success('类目已删除')
    await loadCategories()
  } catch (err) {
    ElMessage.error(describeError(err))
  }
}

watch(() => props.appid, refreshAll)
onMounted(refreshAll)
defineExpose({ reload: refreshAll })
</script>

<template>
  <div class="category-tab">
    <div class="grid-two-col">
      <section class="panel panel--flat" v-loading="loading">
        <h3 class="panel__title">
          <span>已设置类目</span>
          <el-button class="panel__more" text :loading="loading" @click="refreshAll">
            <el-icon><Refresh /></el-icon>
            <span>刷新</span>
          </el-button>
        </h3>

        <el-alert v-if="errorText" type="error" show-icon :closable="false" :title="errorText" class="mb-12" />

        <p v-if="quotaText" class="muted category-tab__tip mb-12">{{ quotaText }}</p>

        <el-table
          v-if="categories.length"
          :data="categories"
          size="small"
          border
          :show-header="false"
        >
          <el-table-column label="类目路径" min-width="220">
            <template #default="{ $index }: { $index: number }">
              <div class="flex-col" style="gap: 2px">
                <span class="strong">{{ categoryPathText(rowAt($index)) || '未命名类目' }}</span>
                <span class="mono muted">{{ categoryPathIds(rowAt($index)) }}</span>
                <span v-if="categoryAuditReason(rowAt($index))" class="category-tab__reason">
                  {{ categoryAuditReason(rowAt($index)) }}
                </span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="审核状态" width="110">
            <template #default="{ $index }: { $index: number }">
              <el-tag
                v-if="categoryAuditText(rowAt($index))"
                :type="categoryAuditType(rowAt($index))"
                size="small"
                effect="light"
              >
                {{ categoryAuditText(rowAt($index)) }}
              </el-tag>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80" align="right">
            <template #default="{ $index }: { $index: number }">
              <el-button text type="danger" size="small" :icon="Delete" @click="onRemove(rowAt($index))">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <EmptyState
          v-else-if="!loading"
          description="尚未设置服务类目"
          sub-description="小程序必须先设置与营业执照/主体相符的类目，才能提交代码审核。"
        />
      </section>

      <section class="panel panel--flat">
        <h3 class="panel__title">
          <span>新增类目</span>
          <span v-if="treeLoading" class="muted">类目树加载中…</span>
        </h3>

        <el-alert
          v-if="treeError"
          type="warning"
          show-icon
          :closable="false"
          :title="`可选类目树获取失败：${treeError}`"
          class="mb-12"
        />

        <el-form label-position="top" @submit.prevent="onAdd">
          <el-form-item label="选择类目（微信只给到一级 / 二级）">
            <el-cascader
              v-model="form.path"
              :options="cascaderOptions"
              :props="cascaderProps"
              :loading="treeLoading"
              clearable
              filterable
              placeholder="可选类目由微信按主体类型下发，需选到二级"
              style="width: 100%"
            />
          </el-form-item>
          <div v-if="selectedQualify.length" class="category-tab__qualify mb-12">
            <span class="strong">该类目需提交资质：</span>
            <span>{{ selectedQualify.join('、') }}</span>
            <p class="muted">
              站内还不能上传资质图片，直接提交微信会返回 53311（需要提交资料信息）；请先在小程序后台「设置 → 基本设置 →
              服务类目」提交该类目与资质。
            </p>
          </div>
          <el-form-item>
            <el-button type="primary" :loading="adding" :icon="Plus" @click="onAdd">提交类目</el-button>
            <span class="muted category-tab__tip">
              部分类目提交后需微信审核，审核结果（审核中 / 不通过及原因）会显示在左侧列表。
            </span>
          </el-form-item>
        </el-form>

        <div v-if="!cascaderOptions.length && !treeLoading && !treeError" class="muted category-tab__tip">
          微信没有下发这个主体的可选类目。不同主体类型允许设置的类目不同；若授权时未勾选类目管理相关权限集（接口归属权限集
          id 30），请在「扫码授权」页重新授权后刷新本页。
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.panel--flat {
  padding: 14px 16px;
}

.mb-12 {
  margin-bottom: 12px;
}

.category-tab__tip {
  font-size: 12px;
  line-height: 1.6;
}

.category-tab__reason {
  font-size: 12px;
  color: var(--el-color-warning);
}

.category-tab__qualify {
  padding: 8px 10px;
  border: 1px solid var(--el-color-warning-light-7);
  border-radius: 6px;
  background: var(--el-color-warning-light-9);
  font-size: 13px;
  line-height: 1.6;
}
</style>
