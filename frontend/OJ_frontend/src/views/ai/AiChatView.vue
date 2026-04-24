<script setup lang="ts">
import { ref, reactive, nextTick, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import { getAiChatHistory, clearAiChat, getAiChatSessions } from '@/api/ai'
import { fetchSSE } from '@/utils/sse'
import type { AiChatRecord } from '@/types'
import MdViewer from '@/components/MdViewer.vue'

interface ChatMessage {
  role: 'user' | 'ai'
  content: string
  loading?: boolean
}

const router = useRouter()
const chatId = ref('')
const sessions = reactive<{ id: string; label: string }[]>([])
const messages = ref<ChatMessage[]>([])
const inputText = ref('')
const sending = ref(false)
const historyLoading = ref(false)
const rateLimitMsg = ref('')
const chatAreaRef = ref<HTMLElement | null>(null)
let sseController: AbortController | null = null

function truncateLabel(text: string, max = 20) {
  return text.length > max ? text.slice(0, max) + '...' : text
}

async function loadSessions() {
  try {
    const res = await getAiChatSessions()
    const list = res.data.data ?? []
    sessions.length = 0
    for (const s of list) {
      sessions.push({ id: s.chatId, label: truncateLabel(s.label || s.chatId) })
    }
    if (sessions.length > 0) {
      chatId.value = sessions[0].id
      loadHistory()
    } else {
      handleNewSession()
    }
  } catch {
    handleNewSession()
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (chatAreaRef.value) {
      chatAreaRef.value.scrollTop = chatAreaRef.value.scrollHeight
    }
  })
}

async function loadHistory() {
  historyLoading.value = true
  try {
    const res = await getAiChatHistory(chatId.value)
    const records: AiChatRecord[] = res.data.data ?? []
    messages.value = []
    for (const r of records) {
      messages.value.push({ role: 'user', content: r.question })
      messages.value.push({ role: 'ai', content: r.answer })
    }
    scrollToBottom()
  } catch (err: any) {
    if (!err?.isRateLimit) {
      Message.error(err?.message || '加载历史失败')
    }
  } finally {
    historyLoading.value = false
  }
}

function handleSend() {
  const text = inputText.value.trim()
  if (!text || sending.value) return

  const session = sessions.find(s => s.id === chatId.value)
  if (session && session.label === '新会话') {
    session.label = truncateLabel(text)
  }

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
    const idx = sessions.findIndex(s => s.id === chatId.value)
    if (idx !== -1) sessions.splice(idx, 1)
    if (sessions.length > 0) {
      chatId.value = sessions[0].id
      loadHistory()
    } else {
      handleNewSession()
    }
    Message.success('会话已清空')
  } catch (err: any) {
    Message.error(err?.message || '清空失败')
  }
}

function handleNewSession() {
  if (sseController) { sseController.abort(); sseController = null }
  sending.value = false
  const newId = 'chat_' + Date.now()
  sessions.unshift({ id: newId, label: '新会话' })
  chatId.value = newId
  messages.value = []
  rateLimitMsg.value = ''
}

function switchSession(id: string) {
  if (id === chatId.value) return
  if (sseController) { sseController.abort(); sseController = null }
  sending.value = false
  chatId.value = id
  rateLimitMsg.value = ''
  loadHistory()
}

function handleLinkClick(e: MouseEvent) {
  const target = e.target as HTMLElement
  const anchor = target.closest('a')
  if (!anchor) return
  const href = anchor.getAttribute('href')
  if (href && href.startsWith('/')) {
    e.preventDefault()
    router.push(href)
  }
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}

onMounted(() => loadSessions())
onUnmounted(() => { if (sseController) sseController.abort() })
</script>

<template>
  <div class="ai-chat-page">
    <!-- 左侧会话列表 -->
    <div class="session-panel">
      <div class="session-header">
        <span class="session-title">会话列表</span>
        <a-button type="text" size="small" @click="handleNewSession">+ 新建</a-button>
      </div>
      <div class="session-list">
        <div
          v-for="s in sessions"
          :key="s.id"
          :class="['session-item', { active: s.id === chatId }]"
          @click="switchSession(s.id)"
        >
          {{ s.label }}
        </div>
      </div>
    </div>

    <!-- 右侧对话区 -->
    <div class="chat-panel">
      <div class="chat-header">
        <span class="chat-title">AI 编程助手</span>
        <a-button size="small" @click="handleClear">清空对话</a-button>
      </div>

      <div ref="chatAreaRef" class="chat-area" @click="handleLinkClick">
        <a-spin :loading="historyLoading" style="width: 100%">
          <div v-if="messages.length === 0 && !historyLoading" class="empty-hint">
            发送消息开始对话
          </div>
          <div
            v-for="(msg, idx) in messages"
            :key="idx"
            :class="['msg-row', msg.role]"
          >
            <div class="msg-avatar">
              {{ msg.role === 'user' ? '我' : 'AI' }}
            </div>
            <div class="msg-bubble">
              <a-spin v-if="msg.loading" size="16" />
              <MdViewer v-else-if="msg.role === 'ai'" :content="msg.content" />
              <div v-else class="msg-text">{{ msg.content }}</div>
            </div>
          </div>
        </a-spin>
      </div>

      <div v-if="rateLimitMsg" class="rate-limit-bar">
        ⚠️ {{ rateLimitMsg }}
      </div>

      <div class="input-area">
        <a-textarea
          v-model="inputText"
          placeholder="输入你的问题..."
          :auto-size="{ minRows: 1, maxRows: 4 }"
          :disabled="sending || !!rateLimitMsg"
          @keydown="handleKeydown"
        />
        <a-button
          type="primary"
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
.ai-chat-page {
  display: grid;
  grid-template-columns: 220px 1fr;
  gap: 16px;
  height: calc(100vh - 104px);
}

.session-panel {
  background: #fff;
  border-radius: 8px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.session-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.session-title {
  font-size: 14px;
  font-weight: 600;
  color: #262626;
}

.session-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.session-item {
  padding: 8px 12px;
  border-radius: 6px;
  font-size: 13px;
  color: #595959;
  cursor: pointer;
  transition: background 0.15s;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-item:hover {
  background: #f7f8fa;
}

.session-item.active {
  background: #fff7e6;
  color: #ffa116;
  font-weight: 500;
}

.chat-panel {
  background: #fff;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.chat-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid #ebebeb;
  flex-shrink: 0;
}

.chat-title {
  font-size: 16px;
  font-weight: 600;
  color: #262626;
}

.chat-area {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.empty-hint {
  text-align: center;
  color: #8c8c8c;
  font-size: 14px;
  margin-top: 40%;
}

.msg-row {
  display: flex;
  gap: 12px;
  max-width: 80%;
}

.msg-row.user {
  align-self: flex-end;
  flex-direction: row-reverse;
}

.msg-row.ai {
  align-self: flex-start;
}

.msg-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  flex-shrink: 0;
}

.msg-row.user .msg-avatar {
  background: #ffa116;
  color: #fff;
}

.msg-row.ai .msg-avatar {
  background: #e8f3ff;
  color: #165dff;
}

.msg-bubble {
  padding: 10px 14px;
  border-radius: 10px;
  font-size: 14px;
  line-height: 1.6;
  min-width: 40px;
}

.msg-row.user .msg-bubble {
  background: #fff7e6;
  color: #262626;
}

.msg-row.ai .msg-bubble {
  background: #f7f8fa;
  color: #262626;
}

.msg-text {
  white-space: pre-wrap;
  word-break: break-word;
}

.rate-limit-bar {
  padding: 8px 20px;
  background: #fff2f0;
  color: #cf1322;
  font-size: 13px;
  border-top: 1px solid #ffccc7;
  flex-shrink: 0;
}

.input-area {
  display: flex;
  gap: 12px;
  padding: 16px 20px;
  border-top: 1px solid #ebebeb;
  align-items: flex-end;
  flex-shrink: 0;
}

.input-area :deep(.arco-textarea-wrapper) {
  flex: 1;
}
</style>
