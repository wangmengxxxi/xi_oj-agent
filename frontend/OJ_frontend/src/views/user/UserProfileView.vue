<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { Message } from '@arco-design/web-vue'
import { useUserStore } from '@/stores/user'
import { updateMyUser } from '@/api/user'

const userStore = useUserStore()
const user = computed(() => userStore.loginUser)

const saving = ref(false)

const form = reactive({
  userName: '',
  userProfile: '',
  userAvatar: '',
})

function initForm() {
  form.userName = user.value.userName ?? ''
  form.userProfile = user.value.userProfile ?? ''
  form.userAvatar = user.value.userAvatar ?? ''
}

async function handleSave() {
  if (!form.userName.trim()) {
    Message.warning('昵称不能为空')
    return
  }
  saving.value = true
  try {
    await updateMyUser({
      userName: form.userName,
      userProfile: form.userProfile || undefined,
      userAvatar: form.userAvatar || undefined,
    })
    // 刷新 store 中的用户信息
    await userStore.fetchLoginUser()
    Message.success('保存成功')
  } catch (err: any) {
    Message.error(err?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

const roleLabels: Record<string, string> = {
  admin: '管理员',
  user: '普通用户',
  ban: '已封禁',
}

const roleColors: Record<string, string> = {
  admin: '#ffa116',
  user: '#00af9b',
  ban: '#ef4743',
}

onMounted(() => initForm())
</script>

<template>
  <div class="profile-page">
    <!-- 个人信息卡片 -->
    <div class="profile-card">
      <div class="profile-left">
        <a-avatar :size="72" :image-url="user.userAvatar || undefined" class="profile-avatar">
          {{ user.userName?.[0]?.toUpperCase() }}
        </a-avatar>
        <div class="profile-meta">
          <div class="profile-name">{{ user.userName || '未设置昵称' }}</div>
          <div class="profile-account">账号：{{ user.id }}</div>
          <a-tag
            :color="roleColors[user.userRole] || 'gray'"
            size="small"
            style="margin-top: 6px"
          >
            {{ roleLabels[user.userRole] || user.userRole }}
          </a-tag>
        </div>
      </div>
      <div v-if="user.userProfile" class="profile-bio">
        {{ user.userProfile }}
      </div>
    </div>

    <!-- 编辑信息卡片 -->
    <div class="edit-card">
      <h3 class="edit-title">编辑个人信息</h3>
      <a-form :model="form" layout="vertical" style="max-width: 480px">
        <a-form-item label="昵称" field="userName">
          <a-input
            v-model="form.userName"
            placeholder="请输入昵称"
            :max-length="20"
            show-word-limit
          />
        </a-form-item>
        <a-form-item label="个人简介" field="userProfile">
          <a-textarea
            v-model="form.userProfile"
            placeholder="介绍一下自己吧"
            :max-length="200"
            show-word-limit
            :auto-size="{ minRows: 3, maxRows: 5 }"
          />
        </a-form-item>
        <a-form-item label="头像 URL" field="userAvatar">
          <a-input
            v-model="form.userAvatar"
            placeholder="请输入头像图片链接（选填）"
          />
        </a-form-item>
        <a-form-item>
          <a-button
            type="primary"
            :loading="saving"
            @click="handleSave"
          >
            保存修改
          </a-button>
          <a-button style="margin-left: 12px" @click="initForm">重置</a-button>
        </a-form-item>
      </a-form>
    </div>
  </div>
</template>

<style scoped>
.profile-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.profile-card {
  background: #fff;
  border-radius: 8px;
  padding: 28px 32px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.profile-left {
  display: flex;
  align-items: center;
  gap: 20px;
}

.profile-avatar {
  flex-shrink: 0;
  background: #ffa116;
  font-size: 28px;
  font-weight: 600;
}

.profile-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.profile-name {
  font-size: 22px;
  font-weight: 600;
  color: #262626;
}

.profile-account {
  font-size: 13px;
  color: #8c8c8c;
}

.profile-bio {
  font-size: 14px;
  color: #595959;
  line-height: 1.6;
  padding-top: 4px;
  border-top: 1px solid #ebebeb;
}

.edit-card {
  background: #fff;
  border-radius: 8px;
  padding: 28px 32px;
}

.edit-title {
  font-size: 16px;
  font-weight: 600;
  color: #262626;
  margin: 0 0 20px 0;
}
</style>
