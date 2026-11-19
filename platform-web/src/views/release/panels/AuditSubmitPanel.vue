<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete, DocumentCopy, Plus, Promotion, Refresh } from '@element-plus/icons-vue'

import { listAllCategories } from '@/api/config'
import { listPages, submitAudit, type AuditKeywordItem, type AuditSubmitItem } from '@/api/release'
import { normalizeCategoryTree, resolveCategoryRefs, toCascaderOptions, type CategoryNode } from '@/utils/category'
import { describeError } from '@/utils/request'

const props = defineProps<{ appid: string }>()

const emit = defineEmits<{ (e: 'submitted', auditId: string): void }>()

/** 一个页面对应微信 item_list 里的一条：页面路径 + query + 类目（名称与 id 成对） */
interface ItemRow {
  key: number
  page: string
  query: string
  title: string
  path: number[]
  keywords: AuditKeywordItem[]
}

const submitting = ref(false)
const errorText = ref('')
const auditId = ref('')
const treeLoading = ref(false)
const tree = ref<CategoryNode[]>([])
let rowKey = 0

const feedbackInfo = ref('')
const versionDesc = ref('')
const items = ref<ItemRow[]>([])

/** 微信侧当前代码包里的页面路径（get_page 的 page_list），提审 address 必须是其中之一 */
const packagePages = ref<string[]>([])
const pagesLoading = ref(false)

async function loadPages(): Promise<void> {
  if (!props.appid) {
    packagePages.value = []
    return
  }
  pagesLoading.value = true
  try {
    const data = await listPages(props.appid)
    const list = (data as { page_list?: unknown }).page_list
    packagePages.value = Array.isArray(list) ? list.filter((item): item is string => typeof item === 'string') : []
  } catch {
    // 没读到清单只是没有候选，提审照样能填
    packagePages.value = []
  } finally {
    pagesLoading.value = false
  }
}

function pageSuggestions(query: string): { value: string }[] {
  const keyword = query.trim().toLowerCase()
  return packagePages.value
    .filter((page) => !keyword || page.toLowerCase().includes(keyword))
    .slice(0, 30)
    .map((page) => ({ value: page }))
}

/** 清单读到了、但填的页面不在里面——微信会直接拒绝这次提审 */
function pageUnknown(row: ItemRow): boolean {
  const page = row.page.trim().split('?')[0]
  if (!page || packagePages.value.length === 0) return false
  return !packagePages.value.includes(page)
}

onMounted(() => void loadPages())
watch(() => props.appid, () => void loadPages())

const cascaderOptions = computed(() => toCascaderOptions(tree.value))
const cascaderProps = {
  value: 'value',
  label: 'label',
  children: 'children',
  expandTrigger: 'hover' as const,
  // 微信只强制一级 + 二级，三级可选，所以每一级都要能单独选中
  checkStrictly: true
}

function nextKey(): number {
  rowKey += 1
  return rowKey
}

function addItemRow(): void {
  items.value.push({ key: nextKey(), page: '', query: '', title: '', path: [], keywords: [] })
}

function removeItemRow(index: number): void {
  items.value.splice(index, 1)
}

function addKeywordRow(row: ItemRow): void {
  row.keywords.push({ keyword: '', hint: '' })
}

function removeKeywordRow(row: ItemRow, index: number): void {
  row.keywords.splice(index, 1)
}

/** 页面路径 + query 合成微信要求的 address */
function buildAddress(row: ItemRow): string {
  const page = row.page.trim()
  const query = row.query.trim()
  if (!page) return ''
  return query ? `${page}?${query.replace(/^\?+/, '')}` : page
}

/** 类目 id 路径回查成 {id,name}，回查不到说明类目树还没加载好 */
function categoryRefs(row: ItemRow) {
  return resolveCategoryRefs(tree.value, row.path)
}

function categoryNames(row: ItemRow): string {
  return categoryRefs(row)
    .map((node) => node.name)
    .join(' / ')
}

function buildItem(row: ItemRow): AuditSubmitItem | null {
  const address = buildAddress(row)
  if (!address || row.path.length < 2) return null
  const [first, second, third] = categoryRefs(row)
  if (!first?.name || !second?.name) return null
  const keywords = row.keywords
    .map((item) => ({
      keyword: (item.keyword ?? '').trim(),
      hint: (item.hint ?? '').trim()
    }))
    .filter((item) => item.keyword)
  return {
    address,
    title: row.title.trim() || undefined,
    firstClass: first.name,
    firstId: first.id,
    secondClass: second.name,
    secondId: second.id,
    thirdClass: third?.name,
    thirdId: third?.name ? third.id : undefined,
    keyWordList: keywords.length ? keywords : undefined
  }
}

async function loadTree(): Promise<void> {
  if (!props.appid) return
  treeLoading.value = true
  try {
    tree.value = normalizeCategoryTree(await listAllCategories(props.appid))
  } catch {
    tree.value = []
  } finally {
    treeLoading.value = false
  }
}

async function onSubmit(): Promise<void> {
  if (!props.appid) {
    ElMessage.warning('请先选择小程序')
    return
  }
  const filled = items.value.filter((row) => buildAddress(row))
  const itemList = items.value
    .map((row) => buildItem(row))
    .filter((row): row is AuditSubmitItem => row !== null)
  if (!itemList.length) {
    if (filled.length && !tree.value.length) {
      ElMessage.warning('类目树未加载，无法取到类目名称与 id，请先点「重载类目」')
    } else {
      ElMessage.warning('请至少填写一个页面路径，并选到一级 + 二级类目')
    }
    return
  }
  if (itemList.length !== filled.length) {
    ElMessage.warning('有页面的类目没选到二级，请补全后再提交')
    return
  }

  submitting.value = true
  errorText.value = ''
  auditId.value = ''
  try {
    const id = await submitAudit(props.appid, {
      itemList,
      feedbackInfo: feedbackInfo.value.trim() || undefined,
      versionDesc: versionDesc.value.trim() || undefined
    })
    auditId.value = String(id ?? '')
    ElMessage.success(`审核已提交，auditId=${auditId.value || '未返回'}`)
    emit('submitted', auditId.value)
  } catch (err) {
    errorText.value = describeError(err)
  } finally {
    submitting.value = false
  }
}

function copyLatestAuditId(): void {
  const value = auditId.value
  if (!value) return
  void navigator.clipboard?.writeText(value)
  ElMessage.success('auditId 已复制')
}

watch(() => props.appid, () => {
  errorText.value = ''
  auditId.value = ''
  void loadTree()
})

onMounted(() => {
  void loadTree()
  if (!items.value.length) addItemRow()
})

defineExpose({ reload: loadTree })
</script>

<template>
  <div class="audit-submit">
    <el-form label-width="128px" @submit.prevent="onSubmit">
      <el-form-item label="审核页面">
        <div class="audit-submit__rows">
          <div v-if="treeLoading" class="muted">可选类目加载中…</div>
          <div v-else-if="!cascaderOptions.length" class="muted audit-submit__hint">
            未取到可选类目树：微信要求同时提交类目名称与类目 id，请点「重载类目」后再选择。
          </div>

          <div style="display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--el-text-color-secondary)">
            <span v-if="pagesLoading">正在读取代码包页面清单…</span>
            <span v-else-if="packagePages.length">代码包里有 {{ packagePages.length }} 个页面，页面路径可直接选</span>
            <span v-else>没读到代码包页面清单（先上传代码或检查权限集 18），路径仍可手动填写</span>
            <el-button text type="primary" size="small" :icon="Refresh" :loading="pagesLoading" @click="loadPages()">
              重新读取页面清单
            </el-button>
          </div>

          <div v-for="(row, index) in items" :key="row.key" class="audit-submit__item">
            <div class="audit-submit__item-head">
              <span class="strong">第 {{ index + 1 }} 项</span>
              <el-button text type="danger" size="small" :icon="Delete" @click="removeItemRow(index)">
                删除
              </el-button>
            </div>
            <div class="audit-submit__row">
              <el-autocomplete
                v-model="row.page"
                :fetch-suggestions="pageSuggestions"
                placeholder="页面路径，如 pages/index/index"
                class="audit-submit__page"
                clearable
              />
              <el-input v-model="row.query" placeholder="query 参数，如 id=1" class="audit-submit__query" />
            </div>
            <p v-if="pageUnknown(row)" style="margin: 4px 0 0; font-size: 12px; color: var(--el-color-warning)">
              当前代码包里没有 {{ row.page.trim().split('?')[0] }} 这个页面，微信会以「页面不存在」拒绝这次提审；
              先确认代码已上传，或点上方「重新读取页面清单」。
            </p>
            <el-cascader
              v-model="row.path"
              :options="cascaderOptions"
              :props="cascaderProps"
              :loading="treeLoading"
              clearable
              filterable
              placeholder="对应类目（一级/二级/三级）"
              class="audit-submit__cascader"
            />
            <div class="audit-submit__grid">
              <el-input v-model="row.title" placeholder="页面标题，如 会员注册" />
            </div>

            <div class="audit-submit__keywords">
              <div v-for="(keyword, kIndex) in row.keywords" :key="kIndex" class="audit-submit__row">
                <el-input v-model="keyword.keyword" placeholder="页面内的关键词，如 会员费" class="audit-submit__page" />
                <el-input v-model="keyword.hint" placeholder="关键词所在路径提示" class="audit-submit__query" />
                <el-button text type="danger" :icon="Delete" @click="removeKeywordRow(row, kIndex)">删除</el-button>
              </div>
              <el-button text :icon="Plus" @click="addKeywordRow(row)">添加关键词</el-button>
            </div>

            <p v-if="row.path.length >= 2" class="muted audit-submit__hint">
              已选类目：{{ categoryNames(row) || '类目树未加载，请先「重载类目」' }}
            </p>
          </div>

          <el-button text type="primary" :icon="Plus" @click="addItemRow">添加审核页面</el-button>
        </div>
      </el-form-item>

      <el-form-item label="版本说明">
        <el-input
          v-model="versionDesc"
          type="textarea"
          :rows="2"
          placeholder="本次提审改了什么，方便审核人员核对"
          class="audit-submit__desc"
        />
      </el-form-item>

      <el-form-item label="审核反馈">
        <el-input
          v-model="feedbackInfo"
          type="textarea"
          :rows="2"
          placeholder="选填，需要审核人员特别注意的说明（如协会内部使用、需登录会员）"
          class="audit-submit__desc"
        />
      </el-form-item>

      <el-form-item>
        <el-button type="primary" :loading="submitting" :icon="Promotion" @click="onSubmit">提交审核</el-button>
        <el-button :loading="treeLoading" :icon="Refresh" text @click="loadTree">重载类目</el-button>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="errorText"
      class="audit-submit__alert"
      type="error"
      show-icon
      :closable="false"
      title="提交审核失败"
      :description="errorText"
    />

    <div v-if="auditId" class="audit-submit__result">
      <span class="muted">auditId</span>
      <span class="mono strong">{{ auditId }}</span>
      <el-button text size="small" :icon="DocumentCopy" @click="copyLatestAuditId">复制</el-button>
      <span class="muted">可切到下一步查询审核进度。</span>
    </div>
  </div>
</template>

<style scoped>
.audit-submit__rows {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.audit-submit__row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.audit-submit__page {
  flex: 2;
}

/* el-autocomplete 的 class 会被 Element Plus 转给内层 el-input，flex 落不到真正的 flex 子项上，只能从行容器往下命中 */
.audit-submit__row :deep(.el-autocomplete) {
  flex: 2;
  min-width: 0;
}

.audit-submit__query {
  flex: 1;
}

.audit-submit__item {
  border: 1px solid var(--app-border-color);
  border-radius: 8px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.audit-submit__item-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
}

.audit-submit__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 8px;
}

.audit-submit__keywords {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.audit-submit__cascader {
  width: 100%;
}

.audit-submit__desc {
  width: 100%;
}

.audit-submit__hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
}

.audit-submit__alert {
  margin-top: 8px;
}

.audit-submit__result {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: #f0f9eb;
  border: 1px solid #d5ecc9;
  border-radius: 8px;
  font-size: 13px;
}
</style>
