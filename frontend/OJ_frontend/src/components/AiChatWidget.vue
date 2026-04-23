<script setup lang="ts">
import { ref, computed, watch, nextTick, onUnmounted } from 'vue'
import { Message } from '@arco-design/web-vue'
import { getAiChatHistory, clearAiChat } from '@/api/ai'
import { fetchSSE } from '@/utils/sse'
import type { AiChatRecord } from '@/types'
import MdViewer from '@/components/MdViewer.vue'

interface ChatMessage {
  role: 'user' | 'ai'
  content: string
  loading?: boolean
}

const props = defineProps<{
  questionId: number | string
  questionTitle?: string
}>()

const expanded = ref(false)
const chatId = computed(() => `q:${props.questionId}`)
const messages = ref<ChatMessage[]>([])
const inputText = ref('')
const sending = ref(false)
const historyLoading = ref(false)
const rateLimitMsg = ref('')
const chatAreaRef = ref<HTMLElement | null>(null)
let sseController: AbortController | null = null
let historyLoaded = false

function scrollToBottom() {
  nextTick(() => {
    if (chatAreaRef.value) {
      chatAreaRef.value.scrollTop = chatAreaRef.value.scrollHeight
    }
  })
}

async function loadHistory() {
  if (historyLoaded) return
  historyLoading.value = true
  try {
    const res = await getAiChatHistory(chatId.value)
    const records: AiChatRecord[] = res.data.data ?? []
    messages.value = []
    for (const r of records) {
      messages.value.push({ role: 'user', content: r.question })
      messages.value.push({ role: 'ai', content: r.answer })
    }
    historyLoaded = true
    scrollToBottom()
  } catch {
    // silent
  } finally {
    historyLoading.value = false
  }
}

function handleSend() {
  const text = inputText.value.trim()
  if (!text || sending.value) return

  rateLimitMsg.value = ''
  messages.value.push({ role: 'user', content: text })
  messages.value.push({ role: 'ai', content: '', loading: true })
  inputText.value = ''
  sending.value = true
  scrollToBottom()

  const aiIdx = messages.value.length - 1

  sseController = fetchSSE('/api/ai/chat/stream', {
    chatId: chatId.value,
    message: text,
    questionId: props.questionId,
  }, {
    onToken(token) {
      messages.value[aiIdx].content += token
      messages.value[aiIdx].loading = false
      scrollToBottom()
    },
    onDone() {
      messages.value[aiIdx].loading = false
      sending.value = false
      scrollToBottom()
    },
    onError(msg) {
      messages.value[aiIdx].loading = false
      sending.value = false
      if (msg.includes('42900') || msg.includes('限') || msg.includes('次数')) {
        rateLimitMsg.value = msg
        messages.value.pop()
        messages.value.pop()
      } else {
        messages.value[aiIdx].content = `请求失败: ${msg}`
      }
    },
  })
}

async function handleClear() {
  try {
    await clearAiChat({ chatId: chatId.value })
    messages.value = []
    Message.success('会话已清空')
  } catch (err: any) {
    Message.error(err?.message || '清空失败')
  }
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}

function toggleExpand() {
  expanded.value = !expanded.value
  if (expanded.value) {
    loadHistory()
  }
}

watch(() => props.questionId, () => {
  if (sseController) { sseController.abort(); sseController = null }
  sending.value = false
  messages.value = []
  rateLimitMsg.value = ''
  historyLoaded = false
  if (expanded.value) {
    loadHistory()
  }
})

onUnmounted(() => { if (sseController) sseController.abort() })
</script>
<template>
  <div class="ai-chat-widget">
    <div v-if="!expanded" class="chat-fab" @click="toggleExpand">AI</div>

    <div v-else class="chat-panel">
      <div class="chat-header">
        <span class="chat-title">AI 助手</span>
        <div class="chat-header-actions">
          <a-button size="mini" type="text" @click="handleClear">清空</a-button>
          <a-button size="mini" type="text" @click="toggleExpand">收起</a-button>
        </div>
      </div>

      <div ref="chatAreaRef" class="chat-messages">
        <a-spin :loading="historyLoading" style="width: 100%">
          <div v-if="messages.length === 0 && !historyLoading" class="empty-hint">
            针对当前题目提问
          </div>
          <div v-for="(msg, idx) in messages" :key="idx" :class="['msg-row', msg.role]">
            <div class="msg-avatar">{{ msg.role === 'user' ? '我' : 'AI' }}</div>
            <div class="msg-bubble">
              <a-spin v-if="msg.loading" size="14" />
              <MdViewer v-else-if="msg.role === 'ai'" :content="msg.content" />
              <div v-else class="msg-text">{{ msg.content }}</div>
            </div>
          </div>
        </a-spin>
      </div>

      <div v-if="rateLimitMsg" class="rate-limit-bar">{{ rateLimitMsg }}</div>

      <div class="chat-input">
        <a-textarea
          v-model="inputText"
          placeholder="问问 AI..."
          :auto-size="{ minRows: 1, maxRows: 3 }"
          :disabled="sending || !!rateLimitMsg"
          @keydown="handleKeydown"
        />
        <a-button
          type="primary"
          size="small"
          :loading="sending"
          :disabled="!inputText.trim() || !!rateLimitMsg"
          @click="handleSend"
        >
          发送
        </a-button>
      </div>
    </div>
  </div>
</template>
<style scoped>
.ai-chat-widget {
  position: fixed;
  bottom: 24px;
  right: 24px;
  z-index: 1000;
}

.chat-fab {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: #ffa116;
  color: #fff;
  font-size: 14px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  transition: transform 0.15s, box-shadow 0.15s;
}

.chat-fab:hover {
  transform: scale(1.08);
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.2);
}

.chat-panel {
  width: 380px;
  height: 520px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.12);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  border-bottom: 1px solid #f0f0f0;
  flex-shrink: 0;
}

.chat-title {
  font-size: 14px;
  font-weight: 600;
  color: #262626;
}

.chat-header-actions {
  display: flex;
  gap: 2px;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.empty-hint {
  text-align: center;
  color: #8c8c8c;
  font-size: 13px;
  padding: 40px 0;
}

.msg-row {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}

.msg-row.user {
  flex-direction: row-reverse;
}

.msg-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: #f0f0f0;
  color: #595959;
  font-size: 11px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.msg-row.ai .msg-avatar {
  background: #ffa116;
  color: #fff;
}

.msg-bubble {
  max-width: 80%;
  padding: 8px 12px;
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.5;
  word-break: break-word;
}

.msg-row.user .msg-bubble {
  background: #e8f3ff;
  color: #262626;
}

.msg-row.ai .msg-bubble {
  background: #f7f8fa;
  color: #262626;
}

.msg-text {
  white-space: pre-wrap;
}

.rate-limit-bar {
  padding: 6px 14px;
  background: #fff2f0;
  color: #cf1322;
  font-size: 12px;
  flex-shrink: 0;
}

.chat-input {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  padding: 10px 12px;
  border-top: 1px solid #f0f0f0;
  flex-shrink: 0;
}

.chat-input :deep(.arco-textarea-wrapper) {
  flex: 1;
}
</style>
