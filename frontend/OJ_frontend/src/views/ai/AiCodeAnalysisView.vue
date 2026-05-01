<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import { getQuestionVOById } from '@/api/question'
import { getAiCodeHistory, getAiSimilarQuestions } from '@/api/ai'
import { listQuestionSubmitByPage } from '@/api/questionSubmit'
import { fetchSSE } from '@/utils/sse'
import type { ApiId, QuestionVO, AiCodeAnalysis } from '@/types'
import CodeEditor from '@/components/CodeEditor.vue'
import MdViewer from '@/components/MdViewer.vue'

const route = useRoute()
const router = useRouter()
const questionId = route.query.questionId ? String(route.query.questionId) : ''
const questionSubmitId = route.query.questionSubmitId ? String(route.query.questionSubmitId) : undefined

const question = ref<QuestionVO | null>(null)
const questionLoading = ref(true)
const analysisResult = ref('')
const analyzing = ref(false)
const historyList = ref<AiCodeAnalysis[]>([])
const historyLoading = ref(false)
const selectedCode = ref('')
const selectedLanguage = ref('')
const similarIds = ref<ApiId[]>([])
let sseController: AbortController | null = null

async function loadQuestion() {
  if (!questionId) {
    Message.error('缺少题目参数')
    router.push('/questions')
    return
  }
  questionLoading.value = true
  try {
    const res = await getQuestionVOById(questionId)
    question.value = res.data.data
  } catch (err: any) {
    Message.error(err?.message || '加载题目失败')
  } finally {
    questionLoading.value = false
  }
}

async function loadHistory() {
  historyLoading.value = true
  try {
    const res = await getAiCodeHistory(questionId, 10)
    historyList.value = res.data.data ?? []
    if (historyList.value.length > 0 && !selectedCode.value) {
      const latest = historyList.value[0]
      selectedCode.value = latest.code
      selectedLanguage.value = latest.language
      analysisResult.value = latest.analysisResult
    }
  } catch (err: any) {
    Message.error(err?.message || '加载历史失败')
  } finally {
    historyLoading.value = false
  }
}

function startAnalysis() {
  if (analyzing.value) return
  analysisResult.value = ''
  analyzing.value = true

  const body: Record<string, unknown> = { questionId }
  if (questionSubmitId) {
    body.questionSubmitId = questionSubmitId
  } else if (selectedCode.value) {
    body.code = selectedCode.value
    body.language = selectedLanguage.value
  }

  sseController = fetchSSE('/api/ai/code/analysis/stream', body, {
    onToken(token) {
      analysisResult.value += token
    },
    onDone() {
      analyzing.value = false
      loadHistory()
      loadSimilar()
    },
    onError(msg) {
      analyzing.value = false
      if (!analysisResult.value) {
        analysisResult.value = `分析失败: ${msg}`
      }
    },
  })
}

async function loadSubmitCode() {
  if (!questionSubmitId) return
  try {
    const res = await listQuestionSubmitByPage({
      questionId,
      current: 1,
      pageSize: 20,
      sortField: 'createTime',
      sortOrder: 'descend',
    })
    const records = res.data.data?.records ?? []
    const target = records.find((r) => String(r.id) === questionSubmitId)
    if (target && target.code) {
      selectedCode.value = target.code
      selectedLanguage.value = target.language
    }
  } catch {
    // 加载提交代码失败不阻塞分析流程
  }
}

function viewHistory(item: AiCodeAnalysis) {
  selectedCode.value = item.code
  selectedLanguage.value = item.language
  analysisResult.value = item.analysisResult
  loadSimilar()
}

async function loadSimilar() {
  try {
    const res = await getAiSimilarQuestions(questionId)
    similarIds.value = res.data.data ?? []
  } catch {
    // 相似题目加载失败不影响主流程
  }
}

function goToQuestion(id: ApiId) {
  router.push(`/view/question/${id}`)
}

onMounted(async () => {
  loadQuestion()
  loadHistory()
  if (questionSubmitId) {
    await loadSubmitCode()
    startAnalysis()
  }
})

onUnmounted(() => { if (sseController) sseController.abort() })
</script>

<template>
  <div class="code-analysis-page">
    <a-spin :loading="questionLoading" style="width: 100%; height: 100%; display: block">
      <div class="split-layout">
        <!-- 左侧：题目 + 代码 -->
        <div class="left-panel">
          <div class="panel-header">
            <h2 class="panel-title">{{ question?.title ?? '加载中...' }}</h2>
            <div class="question-tags">
              <a-tag v-for="tag in (question?.tags ?? [])" :key="tag" color="arcoblue" size="small">
                {{ tag }}
              </a-tag>
            </div>
          </div>

          <div class="code-section">
            <div class="section-label">
              <span>代码</span>
              <a-tag v-if="selectedLanguage" size="small">{{ selectedLanguage }}</a-tag>
            </div>
            <div class="code-editor-wrap">
              <CodeEditor
                v-model="selectedCode"
                :language="selectedLanguage || 'java'"
                :read-only="true"
                height="100%"
              />
            </div>
          </div>

          <a-button
            type="primary"
            :loading="analyzing"
            style="flex-shrink: 0"
            @click="startAnalysis"
          >
            {{ analyzing ? '分析中...' : '重新分析' }}
          </a-button>

          <!-- 历史记录 -->
          <div class="history-section">
            <div class="section-label">历史分析</div>
            <a-spin :loading="historyLoading">
              <div v-if="historyList.length === 0" class="empty-hint">暂无历史记录</div>
              <div
                v-for="item in historyList"
                :key="item.id"
                class="history-item"
                @click="viewHistory(item)"
              >
                <span class="history-lang">{{ item.language }}</span>
                <span v-if="item.score" class="history-score">{{ item.score }}分</span>
                <span class="history-time">{{ item.createTime?.slice(0, 16) }}</span>
              </div>
            </a-spin>
          </div>
        </div>

        <!-- 右侧：分析结果 -->
        <div class="right-panel">
          <div class="panel-header">
            <h2 class="panel-title">AI 分析结果</h2>
          </div>
          <div class="analysis-content">
            <a-spin v-if="analyzing && !analysisResult" />
            <MdViewer v-else-if="analysisResult" :content="analysisResult" />
            <div v-else class="empty-hint">点击「重新分析」开始 AI 代码分析</div>
          </div>
          <div v-if="similarIds.length > 0" class="similar-section">
            <div class="similar-title">相似题目推荐</div>
            <div class="similar-list">
              <a-tag
                v-for="id in similarIds"
                :key="id"
                color="arcoblue"
                class="similar-tag"
                @click="goToQuestion(id)"
              >
                题目 #{{ id }}
              </a-tag>
            </div>
          </div>
        </div>
      </div>
    </a-spin>
  </div>
</template>

<style scoped>
.code-analysis-page {
  height: calc(100vh - 104px);
  overflow: hidden;
}

.split-layout {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  height: 100%;
}

.left-panel,
.right-panel {
  background: #fff;
  border-radius: 8px;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow-y: auto;
}

.panel-header {
  flex-shrink: 0;
}

.panel-title {
  font-size: 18px;
  font-weight: 600;
  color: #262626;
  margin: 0 0 8px;
}

.question-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.code-section {
  flex: 1;
  min-height: 200px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.section-label {
  font-size: 13px;
  font-weight: 600;
  color: #262626;
  display: flex;
  align-items: center;
  gap: 8px;
}

.code-editor-wrap {
  flex: 1;
  border: 1px solid #ebebeb;
  border-radius: 6px;
  min-height: 0;
}

.history-section {
  border-top: 1px solid #ebebeb;
  padding-top: 12px;
  flex-shrink: 0;
}

.history-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
  font-size: 13px;
  border-bottom: 1px solid #f5f5f5;
  cursor: pointer;
  transition: background 0.15s;
}

.history-item:hover {
  background: #f7f8fa;
}

.history-lang {
  color: #595959;
  font-weight: 500;
}

.history-score {
  color: #00af9b;
  font-weight: 500;
}

.history-time {
  color: #8c8c8c;
  margin-left: auto;
}

.analysis-content {
  flex: 1;
  overflow-y: auto;
}

.empty-hint {
  color: #8c8c8c;
  font-size: 14px;
  text-align: center;
  padding: 40px 0;
}

.similar-section {
  border-top: 1px solid #f0f0f0;
  padding-top: 12px;
  flex-shrink: 0;
}

.similar-title {
  font-size: 13px;
  font-weight: 500;
  color: #262626;
  margin-bottom: 8px;
}

.similar-list {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.similar-tag {
  cursor: pointer;
}

.similar-tag:hover {
  opacity: 0.8;
}
</style>
