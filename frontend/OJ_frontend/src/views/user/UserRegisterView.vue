<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import { userRegister } from '@/api/user'

const router = useRouter()

const form = reactive({
  userAccount: '',
  userPassword: '',
  checkPassword: '',
})
const loading = ref(false)

async function handleRegister() {
  if (!form.userAccount || !form.userPassword || !form.checkPassword) {
    Message.warning('请填写所有字段')
    return
  }
  if (form.userPassword.length < 8) {
    Message.warning('密码长度不能少于 8 位')
    return
  }
  if (form.userPassword !== form.checkPassword) {
    Message.warning('两次密码不一致')
    return
  }
  loading.value = true
  try {
    await userRegister(form)
    Message.success('注册成功，请登录')
    router.push('/user/login')
  } catch (err: any) {
    Message.error(err?.message || '注册失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div>
    <h2 class="page-title">创建账号</h2>
    <a-form :model="form" layout="vertical" @submit="handleRegister">
      <a-form-item label="账号" required>
        <a-input
          v-model="form.userAccount"
          placeholder="请输入账号（4-20位）"
          :max-length="20"
          allow-clear
        />
      </a-form-item>
      <a-form-item label="密码" required>
        <a-input-password
          v-model="form.userPassword"
          placeholder="请输入密码（至少8位）"
          :max-length="20"
        />
      </a-form-item>
      <a-form-item label="确认密码" required>
        <a-input-password
          v-model="form.checkPassword"
          placeholder="请再次输入密码"
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
        注册
      </a-button>
    </a-form>
    <div class="form-footer">
      已有账号？
      <router-link to="/user/login">立即登录</router-link>
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
