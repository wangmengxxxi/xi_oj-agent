<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import { listQuestionSubmitByPage } from '@/api/questionSubmit'
import type { QuestionSubmitVO } from '@/types'
import CodeEditor from '@/components/CodeEditor.vue'

const router = useRouter()
const loading = ref(false)
const submits = ref<QuestionSubmitVO[]>([])
const total = ref(0)
const expandedId = ref<number | null>(null)

const filterForm = reactive({
  language: '',
  status: undefined as number | undefined,
  questionId: undefined as number | undefined,
  current: 1,
  pageSize: 20,
})

const statusOptions = [
  { label: '全部', value: undefined },
  { label: '等待中', value: 0 },
  { label: '判题中', value: 1 },
  { label: '通过', value: 2 },
  { label: '失败', value: 3 },
]

const languageOptions = [
  { label: '全部', value: '' },
  { label: 'Java', value: 'java' },
  { label: 'C++', value: 'cpp' },
  { label: 'Go', value: 'go' },
]

const JUDGE_MESSAGE_MAP: Record<string, string> = {
  Accepted: '通过',
  'Wrong Answer': '答案错误',
  'Time Limit Exceeded': '超出时间限制',
  'Memory Limit Exceeded': '超出内存限制',
  'Compile Error': '编译错误',
  'Runtime Error': '运行时错误',
  'System Error': '系统错误',
}

function statusLabel(status: number) {
  const map: Record<number, string> = { 0: '等待中', 1: '判题中', 2: '通过', 3: '失败' }
  return map[status] ?? '未知'
}

function statusClass(status: number) {
  const map: Record<number, string> = {
    0: 'status-pending',
    1: 'status-pending',
    2: 'status-accepted',
    3: 'status-wrong',
  }
  return map[status] ?? 'status-pending'
}

function judgeMessage(vo: QuestionSubmitVO) {
  const msg = vo.judgeInfo?.message
  if (!msg) return statusLabel(vo.status)
  return JUDGE_MESSAGE_MAP[msg] ?? msg
}

function formatTime(dateStr: string) {
  const d = new Date(dateStr)
  const now = Date.now()
  const diff = now - d.getTime()
  if (diff < 60000) return '刚刚'
  if (diff < 3600000) return Math.floor(diff / 60000) + '分钟前'
  if (diff < 86400000) return Math.floor(diff / 3600000) + '小时前'
  return d.toLocaleDateString()
}

async function loadSubmits() {
  loading.value = true
  try {
    const res = await listQuestionSubmitByPage({
      language: filterForm.language || undefined,
      status: filterForm.status,
      questionId: filterForm.questionId || undefined,
      current: filterForm.current,
      pageSize: filterForm.pageSize,
      sortField: 'createTime',
      sortOrder: 'descend',
    })
    submits.value = res.data.data?.records ?? []
    total.value = res.data.data?.total ?? 0
  } catch (err: any) {
    Message.error(err?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  filterForm.current = 1
  loadSubmits()
}

function handlePageChange(page: number) {
  filterForm.current = page
  loadSubmits()
}

function toggleExpand(id: number) {
  expandedId.value = expandedId.value === id ? null : id
}

onMounted(() => loadSubmits())

const columns = [
  { title: '提交时间', slotName: 'createTime', width: 130 },
  { title: '题目', slotName: 'question', width: 160 },
  { title: '语言', dataIndex: 'language', width: 90 },
  { title: '判题状态', slotName: 'status', width: 120 },
  { title: '用时', slotName: 'time', width: 80 },
  { title: '内存', slotName: 'memory', width: 80 },
  { title: '操作', slotName: 'actions', width: 80 },
]
</script>

<template>
  <div class="submit-page">
    <!-- 筛选栏 -->
    <div class="filter-bar">
      <a-select
        v-model="filterForm.language"
        style="width: 120px"
        placeholder="语言"
        @change="handleSearch"
      >
        <a-option
          v-for="opt in languageOptions"
          :key="opt.value"
          :value="opt.value"
        >
          {{ opt.label }}
        </a-option>
      </a-select>
      <a-select
        v-model="filterForm.status"
        style="width: 120px"
        placeholder="状态"
        allow-clear
        @change="handleSearch"
      >
        <a-option
          v-for="opt in statusOptions"
          :key="String(opt.value)"
          :value="opt.value"
        >
          {{ opt.label }}
        </a-option>
      </a-select>
      <a-input-number
        v-model="filterForm.questionId"
        :min="1"
        placeholder="题目ID"
        style="width: 120px"
        allow-clear
        hide-button
      />
      <a-button type="primary" @click="handleSearch">搜索</a-button>
    </div>

    <!-- 提交记录表格 -->
    <a-table
      :columns="columns"
      :data="submits"
      :loading="loading"
      :pagination="false"
      row-key="id"
      :bordered="false"
    >
      <template #createTime="{ record }">
        <span class="time-cell">{{ formatTime(record.createTime) }}</span>
      </template>
      <template #question="{ record }">
        <a
          v-if="record.questionVO"
          class="question-link"
          @click="router.push(`/view/question/${record.questionId}`)"
        >
          {{ record.questionVO.title }}
        </a>
        <span v-else class="text-weak">#{{ record.questionId }}</span>
      </template>
      <template #status="{ record }">
        <span :class="['status-tag', statusClass(record.status)]">
          {{ judgeMessage(record) }}
        </span>
      </template>
      <template #time="{ record }">
        <span class="text-weak">
          {{ record.judgeInfo?.time != null ? record.judgeInfo.time + 'ms' : '-' }}
        </span>
      </template>
      <template #memory="{ record }">
        <span class="text-weak">
          {{ record.judgeInfo?.memory != null ? (record.judgeInfo.memory / 1024).toFixed(1) + 'MB' : '-' }}
        </span>
      </template>
      <template #actions="{ record }">
        <a-button
          v-if="record.code"
          size="mini"
          type="text"
          @click="toggleExpand(record.id)"
        >
          {{ expandedId === record.id ? '收起' : '查看代码' }}
        </a-button>
      </template>
    </a-table>

    <!-- 展开的代码区 -->
    <div v-if="expandedId !== null" class="code-expand">
      <div v-for="s in submits.filter(s => s.id === expandedId)" :key="s.id">
        <div class="code-expand-title">
          {{ s.questionVO?.title ?? '#' + s.questionId }} · {{ s.language }}
        </div>
        <CodeEditor
          :model-value="s.code ?? ''"
          :language="s.language"
          :read-only="true"
          height="300px"
        />
      </div>
    </div>

    <!-- 分页 -->
    <div class="pagination-bar">
      <a-pagination
        :total="total"
        :page-size="filterForm.pageSize"
        :current="filterForm.current"
        show-total
        @change="handlePageChange"
      />
    </div>
  </div>
</template>

<style scoped>
.submit-page {
  background: #fff;
  border-radius: 8px;
  padding: 24px;
}

.filter-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}

.time-cell {
  font-size: 13px;
  color: #8c8c8c;
}

.question-link {
  color: #ffa116;
  cursor: pointer;
  font-weight: 500;
}

.question-link:hover {
  text-decoration: underline;
}

.text-weak {
  color: #8c8c8c;
  font-size: 13px;
}

.code-expand {
  border: 1px solid #ebebeb;
  border-radius: 8px;
  overflow: hidden;
  margin: 16px 0;
}

.code-expand-title {
  padding: 8px 12px;
  font-size: 13px;
  font-weight: 500;
  color: #262626;
  background: #f7f8fa;
  border-bottom: 1px solid #ebebeb;
}

.pagination-bar {
  display: flex;
  justify-content: flex-end;
  margin-top: 20px;
}
</style>
