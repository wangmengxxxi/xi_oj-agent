<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Message, Modal } from '@arco-design/web-vue'
import { listQuestionByPage, deleteQuestion } from '@/api/question'
import type { Question } from '@/types'

const router = useRouter()
const loading = ref(false)
const questions = ref<Question[]>([])
const total = ref(0)

const queryForm = reactive({
  current: 1,
  pageSize: 20,
})

async function loadQuestions() {
  loading.value = true
  try {
    const res = await listQuestionByPage({
      current: queryForm.current,
      pageSize: queryForm.pageSize,
      sortField: 'createTime',
      sortOrder: 'descend',
    })
    questions.value = res.data.data?.records ?? []
    total.value = res.data.data?.total ?? 0
  } catch (err: any) {
    Message.error(err?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function handleEdit(id: number) {
  router.push(`/update/question?id=${id}`)
}

function handleDelete(id: number, title: string) {
  Modal.confirm({
    title: '确认删除',
    content: `确定要删除题目「${title}」吗？此操作不可恢复。`,
    okButtonProps: { status: 'danger' },
    onOk: async () => {
      try {
        await deleteQuestion(id)
        Message.success('删除成功')
        loadQuestions()
      } catch (err: any) {
        Message.error(err?.message || '删除失败')
      }
    },
  })
}

function handlePageChange(page: number) {
  queryForm.current = page
  loadQuestions()
}

function parseTags(tags: string | string[]): string[] {
  if (Array.isArray(tags)) return tags
  if (!tags) return []
  try {
    const parsed = JSON.parse(tags)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

onMounted(() => loadQuestions())

const columns = [
  { title: 'ID', dataIndex: 'id', width: 80 },
  { title: '题目标题', slotName: 'title' },
  { title: '标签', slotName: 'tags', width: 200 },
  { title: '提交数', dataIndex: 'submitNum', width: 90 },
  { title: '通过数', dataIndex: 'acceptedNum', width: 90 },
  { title: '操作', slotName: 'actions', width: 140, fixed: 'right' },
]
</script>

<template>
  <div class="manage-page">
    <!-- 顶部栏 -->
    <div class="manage-header">
      <h2>题目管理</h2>
      <a-button type="primary" @click="router.push('/add/question')">
        + 创建题目
      </a-button>
    </div>

    <!-- 题目表格 -->
    <a-table
      :columns="columns"
      :data="questions"
      :loading="loading"
      :pagination="false"
      row-key="id"
      :bordered="false"
      :scroll="{ x: 900 }"
    >
      <template #title="{ record }">
        <span class="question-title">{{ record.title }}</span>
      </template>
      <template #tags="{ record }">
        <a-space wrap>
          <template v-if="parseTags(record.tags).length > 0">
            <a-tag
              v-for="tag in parseTags(record.tags)"
              :key="tag"
              size="small"
              color="arcoblue"
            >
              {{ tag }}
            </a-tag>
          </template>
          <span v-else style="color: #8c8c8c">—</span>
        </a-space>
      </template>
      <template #actions="{ record }">
        <a-space>
          <a-button
            type="text"
            size="small"
            @click="handleEdit(record.id)"
          >
            编辑
          </a-button>
          <a-button
            type="text"
            size="small"
            status="danger"
            @click="handleDelete(record.id, record.title)"
          >
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
  margin-bottom: 20px;
}

.manage-header h2 {
  font-size: 18px;
  font-weight: 600;
  color: #262626;
  margin: 0;
}

.question-title {
  font-weight: 500;
  color: #262626;
}

.pagination-bar {
  display: flex;
  justify-content: flex-end;
  margin-top: 20px;
}
</style>
