<script setup lang="ts">
</script>

<template>
  <div class="user-layout">
    <div class="user-card">
      <div class="user-logo">XI OJ</div>
      <RouterView />
    </div>
  </div>
</template>

<style scoped>
.user-layout {
  min-height: 100vh;
  background: #f7f8fa;
  display: flex;
  align-items: center;
  justify-content: center;
}

.user-card {
  background: #fff;
  border-radius: 12px;
  padding: 40px;
  width: 400px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
}

.user-logo {
  text-align: center;
  font-size: 28px;
  font-weight: 700;
  color: #ffa116;
  margin-bottom: 32px;
}
</style>
