import axios from 'axios'
import type { AxiosInstance, AxiosResponse } from 'axios'
import router from '@/router'

const request: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 10000,
  withCredentials: true,
})

request.interceptors.response.use(
  (response: AxiosResponse) => {
    const { code, message } = response.data
    if (code === 0) return response
    if (code === 40100) {
      // 未登录，跳转登录页
      if (router.currentRoute.value.path !== '/user/login') {
        router.push('/user/login')
      }
      return Promise.reject(response.data)
    }
    if (code === 40101) {
      router.push('/noAuth')
      return Promise.reject(response.data)
    }
    if (code === 42900) {
      // 限流：不弹通用 Toast，直接透传给调用方
      return Promise.reject({ isRateLimit: true, message, code })
    }
    // 其他业务错误：调用方或全局处理
    return Promise.reject(response.data)
  },
  (error) => {
    return Promise.reject(error)
  },
)

export default request
