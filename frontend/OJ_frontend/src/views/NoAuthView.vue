<script setup lang="ts">
import { useRouter } from 'vue-router'
const router = useRouter()
</script>

<template>
  <div class="noauth-page">
    <div class="noauth-icon">🔒</div>
    <h2>无访问权限</h2>
    <p>您没有权限访问该页面</p>
    <a-button type="primary" @click="router.push('/questions')">返回题库</a-button>
  </div>
</template>

<style scoped>
.noauth-page {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 60vh;
  gap: 12px;
  color: #595959;
}

.noauth-icon {
  font-size: 64px;
}

h2 {
  font-size: 24px;
  color: #262626;
  margin: 0;
}

p {
  color: #8c8c8c;
  margin: 0;
}
</style>
