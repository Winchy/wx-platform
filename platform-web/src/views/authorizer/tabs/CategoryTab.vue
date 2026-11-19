<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Plus, Refresh } from '@element-plus/icons-vue'

import {
  addCategory,
  listAllCategories,
  listCategories,
  removeCategory,
  type AuthorizerCategory
} from '@/api/config'
import { EmptyState } from '@/components'
import {
  categoryDepth,
  categoryPathIds,
  categoryPathText,
  firstThirdId,
  normalizeCategoryTree,
  toCascaderOptions,
  type CategoryNode
} from '@/utils/category'
import { describeError } from '@/utils/request'

const props = defineProps<{ appid: string }>()

const loading = ref(false)
const errorText = ref('')
const categories = ref<AuthorizerCategory[]>([])
const quotaText = ref('')

const treeLoading = ref(false)
const treeError = ref('')
const tree = ref<CategoryNode[]>([])

const adding = ref(false)
const form = reactive<{ path: number[] }>({ path: [] })

const cascaderOptions = computed(() => toCascaderOptions(tree.value))

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
    tree.value = normalizeCategoryTree(await listAllCategories(props.appid))
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
  const first = category.first?.id
  const second = category.second?.id
  if (first === undefined || second === undefined) {
    ElMessage.warning('该类目缺少一二级 ID，无法删除')
    return
  }
  const third = firstThirdId(category)
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
    await removeCategory(props.appid, { first, second, third })
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
              </div>
            </template>
          </el-table-column>
          <el-table-column label="层级" width="90">
            <template #default="{ $index }: { $index: number }">
              <span class="muted">{{ categoryDepth(rowAt($index)) }} 级</span>
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
          <el-form-item label="选择类目（一级 / 二级 / 三级）">
            <el-cascader
              v-model="form.path"
              :options="cascaderOptions"
              :props="cascaderProps"
              :loading="treeLoading"
              clearable
              filterable
              placeholder="例如：社会组织 / 协会 / 商会"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="adding" :icon="Plus" @click="onAdd">提交类目</el-button>
            <span class="muted category-tab__tip">
              部分类目提交后需微信审核，审核通过才会出现在左侧列表。
            </span>
          </el-form-item>
        </el-form>

        <div v-if="!cascaderOptions.length && !treeLoading && !treeError" class="muted category-tab__tip">
          未从 GET /api/authorizers/{appid}/all-categories 解析出类目树，请确认后端返回结构与文档一致。
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
</style>
