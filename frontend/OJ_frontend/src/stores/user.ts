import { ref } from 'vue'
import { defineStore } from 'pinia'
import { getLoginUser } from '@/api/user'
import type { LoginUserVO } from '@/types'

const DEFAULT_USER: LoginUserVO = {
  id: 0,
  userName: '未登录',
  userAvatar: null,
  userProfile: null,
  userRole: 'notLogin',
}

export const useUserStore = defineStore('user', () => {
  const loginUser = ref<LoginUserVO>({ ...DEFAULT_USER })

  async function fetchLoginUser() {
    try {
      const res = await getLoginUser()
      if (res.data.data) {
        loginUser.value = res.data.data
      }
    } catch {
      loginUser.value = { ...DEFAULT_USER }
    }
  }

  function setLoginUser(user: LoginUserVO) {
    loginUser.value = user
  }

  function clearLoginUser() {
    loginUser.value = { ...DEFAULT_USER }
  }

  function isAdmin() {
    return loginUser.value.userRole === 'admin'
  }

  function isLogin() {
    return loginUser.value.userRole !== 'notLogin'
  }

  return { loginUser, fetchLoginUser, setLoginUser, clearLoginUser, isAdmin, isLogin }
})
