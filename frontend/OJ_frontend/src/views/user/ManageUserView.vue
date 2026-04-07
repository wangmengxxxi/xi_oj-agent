<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { Message, Modal } from '@arco-design/web-vue'
import { listUserByPage, updateUser, deleteUser } from '@/api/user'
import type { UserAdmin } from '@/types'

const loading = ref(false)
const users = ref<UserAdmin[]>([])
const total = ref(0)

const queryForm = reactive({
  current: 1,
  pageSize: 20,
  userName: '',
  userRole: '',
})

// 编辑弹窗状态
const editVisible = ref(false)
const editLoading = ref(false)
const editForm = reactive({
  id: 0,
  userName: '',
  userRole: '',
})

async function loadUsers() {
  loading.value = true
  try {
    const params: Record<string, any> = {
      current: queryForm.current,
      pageSize: queryForm.pageSize,
    }
    if (queryForm.userName) params.userName = queryForm.userName
    if (queryForm.userRole) params.userRole = queryForm.userRole

    const res = await listUserByPage(params)
    users.value = res.data.data?.records ?? []
    total.value = res.data.data?.total ?? 0
  } catch (err: any) {
    Message.error(err?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  queryForm.current = 1
  loadUsers()
}

function handleReset() {
  queryForm.userName = ''
  queryForm.userRole = ''
  queryForm.current = 1
  loadUsers()
}

function handleEdit(record: UserAdmin) {
  editForm.id = record.id
  editForm.userName = record.userName ?? ''
  editForm.userRole = record.userRole
  editVisible.value = true
}

async function handleEditSubmit() {
  if (!editForm.userName.trim()) {
    Message.warning('昵称不能为空')
    return
  }
  editLoading.value = true
  try {
    await updateUser({
      id: editForm.id,
      userName: editForm.userName,
      userRole: editForm.userRole,
    })
    Message.success('更新成功')
    editVisible.value = false
    loadUsers()
  } catch (err: any) {
    Message.error(err?.message || '更新失败')
  } finally {
    editLoading.value = false
  }
}

function handleDelete(record: UserAdmin) {
  Modal.confirm({
    title: '确认删除',
    content: `确定要删除用户「${record.userName || record.userAccount}」吗？此操作不可恢复。`,
    okButtonProps: { status: 'danger' },
    onOk: async () => {
      try {
        await deleteUser(record.id)
        Message.success('删除成功')
        loadUsers()
      } catch (err: any) {
        Message.error(err?.message || '删除失败')
      }
    },
  })
}

function handlePageChange(page: number) {
  queryForm.current = page
  loadUsers()
}

const roleMap: Record<string, { label: string; color: string }> = {
  admin: { label: '管理员', color: '#ffa116' },
  user: { label: '普通用户', color: '#00af9b' },
  ban: { label: '已封禁', color: '#ef4743' },
}

function formatDate(dateStr: string) {
  if (!dateStr) return '—'
  return dateStr.slice(0, 10)
}

onMounted(() => loadUsers())

const columns = [
  { title: 'ID', dataIndex: 'id', width: 80 },
  { title: '账号', dataIndex: 'userAccount', width: 140 },
  { title: '昵称', slotName: 'userName', width: 140 },
  { title: '角色', slotName: 'userRole', width: 110 },
  { title: '注册时间', slotName: 'createTime', width: 110 },
  { title: '操作', slotName: 'actions', width: 140, fixed: 'right' },
]
</script>

<template>
  <div class="manage-page">
    <!-- 顶部栏 -->
    <div class="manage-header">
      <h2>用户管理</h2>
    </div>

    <!-- 搜索栏 -->
    <div class="search-bar">
      <a-input
        v-model="queryForm.userName"
        placeholder="搜索昵称"
        style="width: 200px"
        allow-clear
        @press-enter="handleSearch"
      />
      <a-select
        v-model="queryForm.userRole"
        placeholder="角色筛选"
        style="width: 130px"
        allow-clear
      >
        <a-option value="user">普通用户</a-option>
        <a-option value="admin">管理员</a-option>
        <a-option value="ban">已封禁</a-option>
      </a-select>
      <a-button type="primary" @click="handleSearch">搜索</a-button>
      <a-button @click="handleReset">重置</a-button>
    </div>

    <!-- 用户表格 -->
    <a-table
      :columns="columns"
      :data="users"
      :loading="loading"
      :pagination="false"
      row-key="id"
      :bordered="false"
      :scroll="{ x: 800 }"
    >
      <template #userName="{ record }">
        <div class="user-cell">
          <a-avatar :size="28" :image-url="record.userAvatar || undefined" style="flex-shrink:0">
            {{ (record.userName || record.userAccount)?.[0]?.toUpperCase() }}
          </a-avatar>
          <span class="user-name-text">{{ record.userName || '—' }}</span>
        </div>
      </template>
      <template #userRole="{ record }">
        <a-tag
          :color="roleMap[record.userRole]?.color || 'gray'"
          size="small"
        >
          {{ roleMap[record.userRole]?.label || record.userRole }}
        </a-tag>
      </template>
      <template #createTime="{ record }">
        <span style="color: #8c8c8c; font-size: 13px">{{ formatDate(record.createTime) }}</span>
      </template>
      <template #actions="{ record }">
        <a-space>
          <a-button type="text" size="small" @click="handleEdit(record)">编辑</a-button>
          <a-button type="text" size="small" status="danger" @click="handleDelete(record)">
            删除
          </a-button>
        </a-space>
      </template>
    </a-table>

    <!-- 分页 -->
    <div class="pagination-bar">
      <a-pagination
        :total="total"
        :page-size="queryForm.pageSize"
        :current="queryForm.current"
        show-total
        @change="handlePageChange"
      />
    </div>

    <!-- 编辑弹窗 -->
    <a-modal
      v-model:visible="editVisible"
      title="编辑用户"
      :ok-loading="editLoading"
      @ok="handleEditSubmit"
      @cancel="editVisible = false"
    >
      <a-form :model="editForm" layout="vertical">
        <a-form-item label="昵称" field="userName">
          <a-input v-model="editForm.userName" placeholder="请输入昵称" />
        </a-form-item>
        <a-form-item label="角色" field="userRole">
          <a-select v-model="editForm.userRole">
            <a-option value="user">普通用户</a-option>
            <a-option value="admin">管理员</a-option>
            <a-option value="ban">已封禁</a-option>
          </a-select>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<style scoped>
.manage-page {
  background: #fff;
  border-radius: 8px;
  padding: 24px;
}

.manage-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.manage-header h2 {
  font-size: 18px;
  font-weight: 600;
  color: #262626;
  margin: 0;
}

.search-bar {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.user-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.user-name-text {
  font-weight: 500;
  color: #262626;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pagination-bar {
  display: flex;
  justify-content: flex-end;
  margin-top: 20px;
}
</style>
