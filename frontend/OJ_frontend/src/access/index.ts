import router from '@/router'
import { useUserStore } from '@/stores/user'
import { getLoginUser } from '@/api/user'
import type { LoginUserVO } from '@/types'

let initialized = false

router.beforeEach(async (to) => {
  const userStore = useUserStore()

  // 首次加载时初始化登录状态
  if (!initialized) {
    initialized = true
    try {
      const res = await getLoginUser()
      if (res.data.data) {
        userStore.setLoginUser(res.data.data as LoginUserVO)
      }
    } catch {
      // 未登录，保持默认 notLogin 状态
    }
  }

  const requiredRole = to.meta?.requiresRole

  if (!requiredRole) return true

  const user = userStore.loginUser
  if (user.userRole === 'notLogin') {
    return '/user/login'
  }

  if (requiredRole === 'admin' && user.userRole !== 'admin') {
    return '/noAuth'
  }

  return true
})
