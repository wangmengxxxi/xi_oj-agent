<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import { useUserStore } from '@/stores/user'
import { userLogout } from '@/api/user'

const router = useRouter()
const userStore = useUserStore()

const user = computed(() => userStore.loginUser)
const isLogin = computed(() => userStore.isLogin())
const isAdmin = computed(() => userStore.isAdmin())

async function handleLogout() {
  try {
    await userLogout()
    userStore.clearLoginUser()
    router.push('/user/login')
  } catch {
    Message.error('退出登录失败')
  }
}

const navItems = [
  { name: '浏览题目', path: '/questions' },
  { name: '提交记录', path: '/question_submit' },
  { name: '创建题目', path: '/add/question', requireLogin: true },
]
</script>

<template>
  <div class="basic-layout">
    <!-- 顶部导航栏 -->
    <header class="nav-header">
      <div class="nav-inner">
        <!-- Logo -->
        <router-link to="/" class="nav-logo">XI OJ</router-link>

        <!-- 导航菜单 -->
        <nav class="nav-menu">
          <router-link
            v-for="item in navItems"
            :key="item.path"
            :to="item.path"
            class="nav-link"
            active-class="nav-link-active"
          >
            {{ item.name }}
          </router-link>
          <!-- 管理员菜单 -->
          <template v-if="isAdmin">
            <router-link
              to="/manage/question"
              class="nav-link"
              active-class="nav-link-active"
            >
              管理题目
            </router-link>
            <router-link
              to="/manage/user"
              class="nav-link"
              active-class="nav-link-active"
            >
              管理用户
            </router-link>
          </template>
        </nav>

        <!-- 右侧用户区 -->
        <div class="nav-right">
          <template v-if="isLogin">
            <a-dropdown trigger="hover">
              <div class="user-info">
                <a-avatar :size="32" :image-url="user.userAvatar || undefined">
                  {{ user.userName?.[0]?.toUpperCase() }}
                </a-avatar>
                <span class="user-name">{{ user.userName }}</span>
              </div>
              <template #content>
                <a-doption @click="router.push('/profile')">个人中心</a-doption>
                <a-doption @click="handleLogout">退出登录</a-doption>
              </template>
            </a-dropdown>
          </template>
          <template v-else>
            <router-link to="/user/login">
              <a-button type="primary" size="small">登录</a-button>
            </router-link>
          </template>
        </div>
      </div>
    </header>

    <!-- 主内容 -->
    <main class="main-content">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.basic-layout {
  min-height: 100vh;
  background: #f7f8fa;
}

.nav-header {
  background: #fff;
  border-bottom: 1px solid #ebebeb;
  position: sticky;
  top: 0;
  z-index: 100;
}

.nav-inner {
  max-width: 1280px;
  margin: 0 auto;
  height: 56px;
  display: flex;
  align-items: center;
  padding: 0 24px;
  gap: 24px;
}

.nav-logo {
  font-size: 20px;
  font-weight: 700;
  color: #ffa116;
  text-decoration: none;
  flex-shrink: 0;
}

.nav-menu {
  display: flex;
  gap: 4px;
  flex: 1;
}

.nav-link {
  padding: 6px 12px;
  border-radius: 6px;
  font-size: 14px;
  color: #595959;
  text-decoration: none;
  transition: background 0.15s, color 0.15s;
}

.nav-link:hover {
  background: #f7f8fa;
  color: #262626;
}

.nav-link-active {
  color: #ffa116;
  font-weight: 500;
}

.nav-right {
  flex-shrink: 0;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 6px;
  transition: background 0.15s;
}

.user-info:hover {
  background: #f7f8fa;
}

.user-name {
  font-size: 14px;
  color: #262626;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.main-content {
  max-width: 1280px;
  margin: 0 auto;
  padding: 24px;
}
</style>
