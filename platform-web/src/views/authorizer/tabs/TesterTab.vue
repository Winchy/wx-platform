<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search, UserFilled } from '@element-plus/icons-vue'

import { bindTester, listTesters, unbindTester, type TesterItem } from '@/api/config'
import { EmptyState } from '@/components'
import { displayValue } from '@/utils/format'
import { describeError } from '@/utils/request'

const props = defineProps<{ appid: string }>()

const loading = ref(false)
const errorText = ref('')
const rows = ref<TesterItem[]>([])
const keyword = ref('')
const wechatId = ref('')
const binding = ref(false)

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    const data = await listTesters(props.appid)
    rows.value = Array.isArray(data) ? data : []
  } catch (err) {
    errorText.value = describeError(err)
    rows.value = []
  } finally {
    loading.value = false
  }
}

/** 微信的 memberauth 只返回成员唯一标识，微信号可能为空 */
const visibleRows = computed<TesterItem[]>(() => {
  const key = keyword.value.trim().toLowerCase()
  if (!key) return rows.value
  return rows.value.filter(
    (item) =>
      (item.wechatId ?? '').toLowerCase().includes(key) || (item.userstr ?? '').toLowerCase().includes(key)
  )
})

/** el-table 插槽只提供松类型的 row，这里用下标回到强类型数组 */
function rowAt(index: number): TesterItem {
  return visibleRows.value[index]
}

async function onBind(): Promise<void> {
  const value = wechatId.value.trim()
  if (!value) {
    ElMessage.warning('请输入体验成员的微信号')
    return
  }
  binding.value = true
  try {
    await bindTester(props.appid, { wechatId: value })
    ElMessage.success(`已添加体验成员 ${value}`)
    wechatId.value = ''
    await load()
  } catch (err) {
    ElMessage.error(describeError(err))
  } finally {
    binding.value = false
  }
}

async function onUnbind(row: TesterItem): Promise<void> {
  const label = row.wechatId || row.userstr || '该成员'
  try {
    await ElMessageBox.confirm(
      `将移除体验成员「${label}」，其将无法再访问体验版小程序。`,
      '移除体验成员',
      { type: 'warning', confirmButtonText: '移除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await unbindTester(props.appid, row.wechatId ? { wechatId: row.wechatId } : { userStr: row.userstr ?? undefined })
    ElMessage.success('已移除体验成员')
    await load()
  } catch (err) {
    ElMessage.error(describeError(err))
  }
}

watch(() => props.appid, load)
onMounted(load)
defineExpose({ reload: load })
</script>

<template>
  <div class="tester-tab">
    <section class="panel panel--flat">
      <h3 class="panel__title">
        <el-icon><UserFilled /></el-icon>
        <span>体验成员</span>
        <el-button class="panel__more" text :loading="loading" @click="load">
          <el-icon><Refresh /></el-icon>
          <span>刷新</span>
        </el-button>
      </h3>

      <div class="filter-bar">
        <el-input v-model="wechatId" placeholder="体验成员微信号（wxid 或已绑定的账号）" clearable style="width: 300px" @keyup.enter="onBind">
          <template #prefix>
            <el-icon><Plus /></el-icon>
          </template>
        </el-input>
        <el-button type="primary" :loading="binding" @click="onBind">添加</el-button>
        <div class="filter-bar__right">
          <el-input v-model="keyword" placeholder="筛选微信号或成员标识" clearable style="width: 220px">
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
        </div>
      </div>

      <el-alert v-if="errorText" type="error" show-icon :closable="false" :title="errorText" class="mb-12" />

      <el-table v-loading="loading" :data="visibleRows" border size="small" empty-text="暂无体验成员">
        <el-table-column type="index" width="46" />
        <el-table-column label="微信号" min-width="180">
          <template #default="{ $index }: { $index: number }">
            <span class="mono">{{ displayValue(rowAt($index).wechatId, '微信侧未返回微信号') }}</span>
          </template>
        </el-table-column>
        <el-table-column label="成员标识 userstr" min-width="220">
          <template #default="{ $index }: { $index: number }">
            <span class="mono muted">{{ displayValue(rowAt($index).userstr) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" align="right">
          <template #default="{ $index }: { $index: number }">
            <el-button text type="danger" size="small" @click="onUnbind(rowAt($index))">移除</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <EmptyState
            description="还没有体验成员"
            sub-description="体验成员可扫描二维码访问体验版，用于协会内部试用与验收。"
          />
        </template>
      </el-table>

      <p class="muted tester-tab__tip">
        每个小程序最多可配置 15 名体验成员（微信侧限制），添加前请确认微信号正确。
      </p>
    </section>
  </div>
</template>

<style scoped>
.panel--flat {
  padding: 14px 16px;
}

.mb-12 {
  margin-bottom: 12px;
}

.tester-tab__tip {
  margin: 12px 0 0;
  font-size: 12px;
}
</style>
