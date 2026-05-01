<script setup lang="ts">
import { ref, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import { fetchSSE } from '@/utils/sse'
import type { ApiId } from '@/types'
import MdViewer from '@/components/MdViewer.vue'

const props = defineProps<{ questionId: number | string }>()
const router = useRouter()

const expanded = ref(false)
const parseResult = ref('')
const parsing = ref(false)
const similarIds = ref<ApiId[]>([])
const loaded = ref(false)
let sseController: AbortController | null = null

function handleExpand() {
  expanded.value = !expanded.value
  if (expanded.value && !loaded.value) {
    startParse()
  }
}

function startParse() {
  parseResult.value = ''
  parsing.value = true
  loaded.value = true

  sseController = fetchSSE('/api/ai/question/parse/stream', {
    questionId: String(props.questionId),
  }, {
    onToken(token) {
      parseResult.value += token
    },
    onDone() {
      parsing.value = false
      loadSimilar()
    },
    onError(msg) {
      parsing.value = false
      if (!parseResult.value) {
        parseResult.value = `解析失败: ${msg}`
      }
    },
  })
}

async function loadSimilar() {
  try {
    const { getAiSimilarQuestions } = await import('@/api/ai')
    const res = await getAiSimilarQuestions(props.questionId)
    similarIds.value = res.data.data ?? []
  } catch {
    // 相似题目加载失败不影响主流程
  }
}

function goToQuestion(id: ApiId) {
  router.push(`/view/question/${id}`)
}

onUnmounted(() => { if (sseController) sseController.abort() })
</script>

<template>
  <div class="ai-parse-section">
    <div class="parse-header" @click="handleExpand">
      <span class="parse-icon">{{ expanded ? '▼' : '▶' }}</span>
      <span class="parse-title">AI 题目解析</span>
      <a-spin v-if="parsing" size="14" style="margin-left: 8px" />
    </div>

    <div v-if="expanded" class="parse-body">
      <a-spin v-if="parsing && !parseResult" />
      <MdViewer v-else-if="parseResult" :content="parseResult" />
      <div v-else class="empty-hint">点击展开获取 AI 解析</div>

      <div v-if="!parsing && loaded" class="reparse-bar">
        <a-button type="text" size="mini" @click="startParse">重新解析</a-button>
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
</template>

<style scoped>
.ai-parse-section {
  border: 1px solid #ebebeb;
  border-radius: 8px;
  overflow: hidden;
  margin-top: 12px;
}

.parse-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  background: #fafafa;
  cursor: pointer;
  user-select: none;
  transition: background 0.15s;
}

.parse-header:hover {
  background: #f0f0f0;
}

.parse-icon {
  font-size: 10px;
  color: #8c8c8c;
}

.parse-title {
  font-size: 14px;
  font-weight: 500;
  color: #262626;
}

.parse-body {
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.reparse-bar {
  display: flex;
  justify-content: flex-end;
}

.similar-section {
  border-top: 1px solid #f0f0f0;
  padding-top: 10px;
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

.empty-hint {
  text-align: center;
  color: #8c8c8c;
  font-size: 13px;
  padding: 12px 0;
}
</style>
