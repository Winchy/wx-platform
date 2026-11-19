import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { fetchCurrentUser, login as loginApi, logout as logoutApi, type LoginParams, type UserInfo } from '@/api/auth'
import { clearToken, describeError, getToken, setToken } from '@/utils/request'

const USER_STORAGE_KEY = 'wx_platform_user'

function readCachedUser(): UserInfo | null {
  const raw = localStorage.getItem(USER_STORAGE_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as UserInfo
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(getToken())
  const user = ref<UserInfo | null>(readCachedUser())
  const isAuthenticated = computed(() => Boolean(token.value))

  function persistUser(value: UserInfo | null): void {
    if (value) {
      localStorage.setItem(USER_STORAGE_KEY, JSON.stringify(value))
    } else {
      localStorage.removeItem(USER_STORAGE_KEY)
    }
  }

  function setSession(nextToken: string, nextUser: UserInfo | null): void {
    token.value = nextToken
    setToken(nextToken)
    user.value = nextUser
    persistUser(nextUser)
  }

  function reset(): void {
    token.value = ''
    user.value = null
    clearToken()
    persistUser(null)
  }

  async function login(params: LoginParams): Promise<string> {
    const result = await loginApi(params)
    setSession(result.token, result.user ?? null)
    return result.token
  }

  async function loadUser(): Promise<boolean> {
    if (!token.value) return false
    try {
      const me = await fetchCurrentUser()
      user.value = me
      persistUser(me)
      return true
    } catch {
      reset()
      return false
    }
  }

  async function logout(): Promise<string> {
    let message = ''
    try {
      await logoutApi()
    } catch (error) {
      message = describeError(error)
    }
    reset()
    return message
  }

  return {
    token,
    user,
    isAuthenticated,
    setSession,
    reset,
    login,
    loadUser,
    logout
  }
})
