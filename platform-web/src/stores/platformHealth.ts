import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { fetchPlatformHealth, type PlatformHealth } from '@/api/platform'
import { describeError } from '@/utils/request'

/** 概览页轮询用的第三方平台健康状态（票据 12 分钟阈值等） */
export const usePlatformHealthStore = defineStore('platformHealth', () => {
  const health = ref<PlatformHealth | null>(null)
  const loading = ref(false)
  const error = ref('')
  const updatedAt = ref<number>(0)
  const timer = ref<number | null>(null)

  const configReady = computed(() => health.value?.configReady === true)
  const ticketFresh = computed(() => health.value?.ticketFresh === true)
  const componentTokenValid = computed(() => health.value?.componentTokenValid === true)

  async function refresh(): Promise<void> {
    loading.value = true
    try {
      health.value = await fetchPlatformHealth({ silent: true })
      error.value = ''
      updatedAt.value = Date.now()
    } catch (err) {
      error.value = describeError(err)
    } finally {
      loading.value = false
    }
  }

  function start(intervalMs = 20000): void {
    if (timer.value !== null) return
    void refresh()
    timer.value = window.setInterval(() => {
      void refresh()
    }, intervalMs)
  }

  function stop(): void {
    if (timer.value !== null) {
      window.clearInterval(timer.value)
      timer.value = null
    }
  }

  return {
    health,
    loading,
    error,
    updatedAt,
    configReady,
    ticketFresh,
    componentTokenValid,
    refresh,
    start,
    stop
  }
})
