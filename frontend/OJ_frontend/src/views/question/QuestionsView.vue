<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import { listQuestionVOByPage } from '@/api/question'
import type { QuestionVO } from '@/types'

const router = useRouter()

const loading = ref(false)
const questions = ref<QuestionVO[]>([])
const total = ref(0)

const searchForm = reactive({
  title: '',
  tags: [] as string[],
  current: 1,
  pageSize: 20,
})

// 常用标签列表
const commonTags = [
  '数组', '字符串', '哈希表', '动态规划', '数学', '排序',
  '贪心', '深度优先搜索', '广度优先搜索', '双指针', '二分查找',
  '树', '图', '链表', '栈', '队列', '递归', '位运算',
]

async function loadQuestions() {
  loading.value = true
  try {
    const res = await listQuestionVOByPage({
      title: searchForm.title || undefined,
      tags: searchForm.tags.length ? searchForm.tags : undefined,
      current: searchForm.current,
      pageSize: searchForm.pageSize,
      sortField: 'createTime',
      sortOrder: 'descend',
    })
    questions.value = res.data.data?.records ?? []
    total.value = res.data.data?.total ?? 0
  } catch (err: any) {
    Message.error(err?.message || '加载题目失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  searchForm.current = 1
  loadQuestions()
}

function handlePageChange(page: number) {
  searchForm.current = page
  loadQuestions()
}

function calcAcceptRate(q: QuestionVO) {
  if (!q.submitNum) return '-'
  return ((q.acceptedNum / q.submitNum) * 100).toFixed(1) + '%'
}

function toViewQuestion(id: number) {
  router.push(`/view/question/${id}`)
}

onMounted(() => loadQuestions())

const columns = [
  { title: '#', dataIndex: 'id', width: 80 },
  { title: '题目', dataIndex: 'title', slotName: 'title' },
  { title: '标签', dataIndex: 'tags', slotName: 'tags', width: 200 },
  { title: '通过率', dataIndex: 'acceptRate', slotName: 'acceptRate', width: 100 },
  { title: '提交数', dataIndex: 'submitNum', width: 100 },
]
</script>

<template>
  <div class="questions-page">
    <!-- 搜索栏 -->
    <div class="search-bar">
      <a-input
        v-model="searchForm.title"
        placeholder="搜索题目标题..."
        style="width: 300px"
        allow-clear
        @press-enter="handleSearch"
      />
      <a-select
        v-model="searchForm.tags"
        multiple
        placeholder="标签筛选"
        style="width: 280px"
        allow-clear
      >
        <a-option v-for="tag in commonTags" :key="tag" :value="tag">{{ tag }}</a-option>
      </a-select>
      <a-button type="primary" @click="handleSearch">搜索</a-button>
    </div>

    <!-- 题目表格 -->
    <a-table
      :columns="columns"
      :data="questions"
      :loading="loading"
      :pagination="false"
      row-key="id"
      :bordered="false"
      class="questions-table"
      @row-click="(record: QuestionVO) => toViewQuestion(record.id)"
    >
      <template #title="{ record }">
        <span class="question-title">{{ record.title }}</span>
      </template>
      <template #tags="{ record }">
        <a-space wrap>
          <a-tag
            v-for="tag in record.tags"
            :key="tag"
            color="arcoblue"
            size="small"
          >
            {{ tag }}
          </a-tag>
        </a-space>
      </template>
      <template #acceptRate="{ record }">
        <span :style="{ color: record.submitNum ? '#00AF9B' : '#8c8c8c' }">
          {{ calcAcceptRate(record) }}
        </span>
      </template>
    </a-table>

    <!-- 分页 -->
    <div class="pagination-bar">
      <a-pagination
        :total="total"
        :page-size="searchForm.pageSize"
        :current="searchForm.current"
        show-total
        @change="handlePageChange"
      />
    </div>
  </div>
</template>

<style scoped>
.questions-page {
  background: #fff;
  border-radius: 8px;
  padding: 24px;
}

.search-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}

.questions-table {
  cursor: pointer;
}

.question-title {
  color: #262626;
  font-weight: 500;
}

.question-title:hover {
  color: #ffa116;
}

.pagination-bar {
  display: flex;
  justify-content: flex-end;
  margin-top: 20px;
}
</style>
