<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import { userLogin } from '@/api/user'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

const form = reactive({ userAccount: '', userPassword: '' })
const loading = ref(false)

async function handleLogin() {
  if (!form.userAccount || !form.userPassword) {
    Message.warning('请输入账号和密码')
    return
  }
  loading.value = true
  try {
    const res = await userLogin(form)
    if (res.data.data) {
      userStore.setLoginUser(res.data.data)
      Message.success('登录成功')
      router.push('/questions')
    }
  } catch (err: any) {
    Message.error(err?.message || '账号或密码错误')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div>
    <h2 class="page-title">欢迎回来</h2>
    <a-form :model="form" layout="vertical" @submit="handleLogin">
      <a-form-item label="账号" required>
        <a-input
          v-model="form.userAccount"
          placeholder="请输入账号"
          :max-length="20"
          allow-clear
        />
      </a-form-item>
      <a-form-item label="密码" required>
        <a-input-password
          v-model="form.userPassword"
          placeholder="请输入密码"
          :max-length="20"
        />
      </a-form-item>
      <a-button
        type="primary"
        html-type="submit"
        long
        :loading="loading"
        style="margin-top: 8px"
      >
        登录
      </a-button>
    </a-form>
    <div class="form-footer">
      还没有账号？
      <router-link to="/user/register">立即注册</router-link>
    </div>
  </div>
</template>

<style scoped>
.page-title {
  text-align: center;
  font-size: 20px;
  font-weight: 600;
  color: #262626;
  margin-bottom: 24px;
}

.form-footer {
  text-align: center;
  margin-top: 16px;
  font-size: 13px;
  color: #8c8c8c;
}

.form-footer a {
  color: #ffa116;
  text-decoration: none;
}
</style>
