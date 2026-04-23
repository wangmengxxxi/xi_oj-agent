<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import { getQuestionVOById } from '@/api/question'
import { doQuestionSubmit, listQuestionSubmitByPage } from '@/api/questionSubmit'
import type { QuestionVO, QuestionSubmitVO, JudgeInfo } from '@/types'
import MdViewer from '@/components/MdViewer.vue'
import CodeEditor from '@/components/CodeEditor.vue'
import AiQuestionParse from '@/components/AiQuestionParse.vue'
import QuestionComment from '@/components/QuestionComment.vue'
import AiChatWidget from '@/components/AiChatWidget.vue'

const route = useRoute()
const router = useRouter()
const questionId = String(route.params.id)
const leftTab = ref<'description' | 'comment'>('description')

// ===== 题目数据 =====
const question = ref<QuestionVO | null>(null)
const questionLoading = ref(true)

async function loadQuestion() {
  questionLoading.value = true
  try {
    const res = await getQuestionVOById(questionId)
    question.value = res.data.data
  } catch (err: any) {
    Message.error(err?.message || '加载题目失败')
    router.push('/questions')
  } finally {
    questionLoading.value = false
  }
}

// ===== 代码编辑器 =====
const LANGUAGE_OPTIONS = [
  { label: 'Java', value: 'java' },
  { label: 'C++', value: 'cpp' },
  { label: 'Go', value: 'go' },
]

const CODE_TEMPLATES: Record<string, string> = {
  java: `public class Main {
    public static void main(String[] args) {
        // 输入通过命令行参数传入，例如：args[0] = "1", args[1] = "2"
        // 在此编写代码
    }
}`,
  cpp: `#include <bits/stdc++.h>
using namespace std;

int main(int argc, char* argv[]) {
    // 输入通过命令行参数传入，例如：argv[1] = "1", argv[2] = "2"
    // 在此编写代码
    return 0;
}`,
  go: `package main

import (
    "fmt"
    "os"
)

func main() {
    // 输入通过命令行参数传入，例如：os.Args[1] = "1", os.Args[2] = "2"
    _ = os.Args
    // 在此编写代码
    fmt.Println()
}`,
}

const language = ref('java')
const code = ref(CODE_TEMPLATES.java)

async function loadLastSubmit() {
  try {
    const res = await listQuestionSubmitByPage({
      questionId,
      current: 1,
      pageSize: 1,
      sortField: 'createTime',
      sortOrder: 'descend',
    })
    const records = res.data.data?.records ?? []
    if (records.length > 0 && records[0].code) {
      const last = records[0]
      language.value = last.language || 'java'
      code.value = last.code
    }
  } catch {
    // 加载上次提交失败不影响正常使用
  }
}

function handleLanguageChange(val: string) {
  if (code.value !== CODE_TEMPLATES[language.value]) {
    // 用户已修改过代码，提示是否清空
    if (!confirm('切换语言将清空当前代码，是否继续？')) return
  }
  language.value = val
  code.value = CODE_TEMPLATES[val] ?? ''
}

// ===== 提交 =====
const submitting = ref(false)
const rateLimitMsg = ref('')
const rateLimitUntil = ref(0) // 本地限流冷却截止时间戳
const rateLimitType = ref<'cooldown' | 'daily' | ''>('') // 区分冷却型 vs 日级限流

const submitDisabled = computed(() => {
  return submitting.value || (rateLimitUntil.value > Date.now())
})

const cooldownSeconds = ref(0)
let cooldownTimer: ReturnType<typeof setInterval> | null = null

function startCooldown(seconds: number) {
  cooldownSeconds.value = seconds
  if (cooldownTimer) clearInterval(cooldownTimer)
  cooldownTimer = setInterval(() => {
    cooldownSeconds.value--
    if (cooldownSeconds.value <= 0) {
      clearInterval(cooldownTimer!)
      cooldownTimer = null
      rateLimitMsg.value = ''
      rateLimitUntil.value = 0
      rateLimitType.value = ''
    }
  }, 1000)
}

// ===== 判题结果 =====
const judgeResult = ref<{
  status: 'idle' | 'polling' | 'done'
  submitVO: QuestionSubmitVO | null
}>({ status: 'idle', submitVO: null })

let pollTimer: ReturnType<typeof setTimeout> | null = null
let pollCount = 0
const MAX_POLL = 30 // 最多轮询 30 次 × 1.5s = 45s

const JUDGE_MESSAGE_MAP: Record<string, { text: string; cls: string }> = {
  Accepted: { text: '通过', cls: 'status-accepted' },
  'Wrong Answer': { text: '答案错误', cls: 'status-wrong' },
  'Time Limit Exceeded': { text: '超出时间限制', cls: 'status-tle' },
  'Memory Limit Exceeded': { text: '超出内存限制', cls: 'status-tle' },
  'Compile Error': { text: '编译错误', cls: 'status-tle' },
  'Runtime Error': { text: '运行时错误', cls: 'status-wrong' },
  'System Error': { text: '系统错误', cls: 'status-wrong' },
}

function getJudgeDisplay(judgeInfo: JudgeInfo | null) {
  if (!judgeInfo?.message) return { text: '等待中', cls: 'status-pending' }
  return JUDGE_MESSAGE_MAP[judgeInfo.message] ?? { text: judgeInfo.message, cls: 'status-pending' }
}

async function pollJudgeResult(submitId: number) {
  if (pollCount >= MAX_POLL) {
    judgeResult.value.status = 'done'
    Message.warning('判题超时，请稍后查看提交记录')
    return
  }
  pollCount++
  try {
    const res = await listQuestionSubmitByPage({
      questionId,
      current: 1,
      pageSize: 1,
      sortField: 'createTime',
      sortOrder: 'descend',
    })
    const records = res.data.data?.records ?? []
    if (records.length && records[0].id === submitId) {
      const vo = records[0]
      if (vo.status === 0 || vo.status === 1) {
        // 继续轮询
        pollTimer = setTimeout(() => pollJudgeResult(submitId), 1500)
      } else {
        judgeResult.value = { status: 'done', submitVO: vo }
        loadRecentSubmits()
      }
    } else {
      pollTimer = setTimeout(() => pollJudgeResult(submitId), 1500)
    }
  } catch {
    pollTimer = setTimeout(() => pollJudgeResult(submitId), 1500)
  }
}

async function handleSubmit() {
  if (!code.value.trim()) {
    Message.warning('请先填写代码')
    return
  }
  if (rateLimitUntil.value > Date.now()) {
    Message.warning(rateLimitMsg.value)
    return
  }

  submitting.value = true
  judgeResult.value = { status: 'polling', submitVO: null }
  rateLimitMsg.value = ''
  pollCount = 0

  try {
    const res = await doQuestionSubmit({
      language: language.value,
      code: code.value,
      questionId,
    })
    const submitId = res.data.data!
    pollTimer = setTimeout(() => pollJudgeResult(submitId), 1500)
  } catch (err: any) {
    if (err?.isRateLimit) {
      const msg = err.message ?? '提交频率过高，请稍后再试'
      rateLimitMsg.value = msg
      judgeResult.value = { status: 'idle', submitVO: null }

      // 按后端各限流类型匹配冷却策略
      const secsMatch = msg.match(/等待\s*(\d+)\s*秒/)
      if (secsMatch) {
        // USER_QUESTION_COOLDOWN：题目冷却，后端明确告知秒数
        const secs = parseInt(secsMatch[1])
        rateLimitType.value = 'cooldown'
        rateLimitUntil.value = Date.now() + secs * 1000
        startCooldown(secs)
      } else if (msg.includes('每分钟')) {
        // USER_MINUTE：分钟级用户限流，等待 60s
        rateLimitType.value = 'cooldown'
        rateLimitUntil.value = Date.now() + 60 * 1000
        startCooldown(60)
      } else if (msg.includes('明日') || msg.includes('今日')) {
        // USER_DAY：日提交上限，不设倒计时，明日重置
        rateLimitType.value = 'daily'
        rateLimitUntil.value = Date.now() + 86400 * 1000
      } else if (msg.includes('人数过多')) {
        // GLOBAL_SECOND：全局瞬时压力，稍等 10s 重试
        rateLimitType.value = 'cooldown'
        rateLimitUntil.value = Date.now() + 10 * 1000
        startCooldown(10)
      } else {
        // IP_MINUTE 或其他：等待 60s
        rateLimitType.value = 'cooldown'
        rateLimitUntil.value = Date.now() + 60 * 1000
        startCooldown(60)
      }
    } else {
      Message.error(err?.message || '提交失败，请稍后再试')
      judgeResult.value = { status: 'idle', submitVO: null }
    }
  } finally {
    submitting.value = false
  }
}

// ===== 最近提交记录 =====
const recentSubmits = ref<QuestionSubmitVO[]>([])

async function loadRecentSubmits() {
  try {
    const res = await listQuestionSubmitByPage({
      questionId,
      current: 1,
      pageSize: 5,
      sortField: 'createTime',
      sortOrder: 'descend',
    })
    recentSubmits.value = res.data.data?.records ?? []
  } catch {
    // ignore
  }
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

onMounted(() => {
  loadQuestion()
  loadRecentSubmits()
  loadLastSubmit()
})

onUnmounted(() => {
  if (pollTimer) clearTimeout(pollTimer)
  if (cooldownTimer) clearInterval(cooldownTimer)
})
</script>

<template>
  <div class="view-question-page">
    <a-spin :loading="questionLoading" style="width: 100%; height: 100%; display: block">
      <div v-if="question" class="split-layout">
        <!-- 左侧：题目信息 -->
        <div class="left-panel">
          <!-- Tab 切换 -->
          <div class="left-tabs">
            <span
              :class="['tab-item', { active: leftTab === 'description' }]"
              @click="leftTab = 'description'"
            >
              题目描述
            </span>
            <span
              :class="['tab-item', { active: leftTab === 'comment' }]"
              @click="leftTab = 'comment'"
            >
              评论区
            </span>
          </div>

          <!-- 题目描述 Tab -->
          <template v-if="leftTab === 'description'">
            <div class="question-header">
              <h1 class="question-title">{{ question.title }}</h1>
              <div class="question-meta">
                <a-space wrap>
                  <a-tag v-for="tag in question.tags" :key="tag" color="arcoblue" size="small">
                    {{ tag }}
                  </a-tag>
                </a-space>
                <span class="meta-item">
                  提交 {{ question.submitNum }} &nbsp;|&nbsp; 通过 {{ question.acceptedNum }}
                </span>
              </div>
            </div>

            <div class="question-content">
              <MdViewer :content="question.content" />
            </div>

            <!-- AI 题目解析 -->
            <AiQuestionParse :question-id="questionId" />

            <div class="judge-config">
              <span>时间限制：{{ question.judgeConfig?.timeLimit ?? '-' }} ms</span>
              <span>内存限制：{{ (question.judgeConfig?.memoryLimit ?? 0) / 1024 }} MB</span>
            </div>

            <div class="recent-submits">
              <div class="section-title">最近提交</div>
              <div v-if="recentSubmits.length === 0" class="empty-hint">暂无提交记录</div>
              <div
                v-for="s in recentSubmits"
                :key="s.id"
                class="submit-row"
              >
                <span :class="['status-tag', statusClass(s.status)]">
                  {{ statusLabel(s.status) }}
                </span>
                <span class="submit-lang">{{ s.language }}</span>
                <span v-if="s.judgeInfo?.time != null" class="submit-meta">{{ s.judgeInfo.time }}ms</span>
                <span v-if="s.judgeInfo?.memory != null" class="submit-meta">{{ (s.judgeInfo.memory / 1024).toFixed(1) }}MB</span>
              </div>
            </div>
          </template>

          <!-- 评论区 Tab -->
          <template v-if="leftTab === 'comment'">
            <QuestionComment :question-id="questionId" />
          </template>
        </div>

        <!-- 右侧：代码编辑区 -->
        <div class="right-panel">
          <!-- 语言选择 -->
          <div class="editor-toolbar">
            <a-select
              :model-value="language"
              style="width: 120px"
              size="small"
              @change="handleLanguageChange"
            >
              <a-option
                v-for="opt in LANGUAGE_OPTIONS"
                :key="opt.value"
                :value="opt.value"
              >
                {{ opt.label }}
              </a-option>
            </a-select>
          </div>

          <!-- 代码编辑器 -->
          <div class="editor-wrapper">
            <CodeEditor
              v-model="code"
              :language="language"
              height="100%"
            />
          </div>

          <!-- 提交按钮区 -->
          <div class="submit-area">
            <div v-if="rateLimitMsg" class="rate-limit-tip">
              <div class="rate-limit-main">⚠️ {{ rateLimitMsg }}</div>
              <div v-if="cooldownSeconds > 0" class="rate-limit-sub">
                请等待 <strong>{{ cooldownSeconds }}</strong> 秒后重新提交
              </div>
              <div v-else-if="rateLimitType === 'daily'" class="rate-limit-sub">
                今日提交次数已达上限，明日 00:00 自动重置
              </div>
            </div>
            <a-button
              type="primary"
              size="large"
              :loading="submitting"
              :disabled="submitDisabled"
              @click="handleSubmit"
            >
              {{
                submitting ? '提交中...'
                : cooldownSeconds > 0 ? `冷却中（${cooldownSeconds}s）`
                : rateLimitType === 'daily' ? '今日提交已达上限'
                : '提交代码'
              }}
            </a-button>
          </div>

          <!-- 判题结果区 -->
          <div v-if="judgeResult.status !== 'idle'" class="judge-result">
            <div v-if="judgeResult.status === 'polling'" class="polling">
              <a-spin />
              <span>判题中，请稍候...</span>
            </div>
            <div v-else-if="judgeResult.submitVO" class="result-detail">
              <div
                :class="['result-status', getJudgeDisplay(judgeResult.submitVO.judgeInfo).cls]"
              >
                {{ getJudgeDisplay(judgeResult.submitVO.judgeInfo).text }}
              </div>
              <div class="result-meta">
                <span v-if="judgeResult.submitVO.judgeInfo?.time != null">
                  执行用时：{{ judgeResult.submitVO.judgeInfo.time }} ms
                </span>
                <span v-if="judgeResult.submitVO.judgeInfo?.memory != null">
                  内存消耗：{{ (judgeResult.submitVO.judgeInfo.memory / 1024).toFixed(1) }} MB
                </span>
                <a-button
                  type="text"
                  size="small"
                  @click="router.push(`/ai/code-analysis?questionId=${questionId}&questionSubmitId=${judgeResult.submitVO?.id}`)"
                >
                  AI 分析
                </a-button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </a-spin>

    <AiChatWidget
      v-if="question"
      :question-id="questionId"
      :question-title="question?.title"
    />
  </div>
</template>

<style scoped>
.view-question-page {
  height: calc(100vh - 104px);
  overflow: hidden;
}

.left-tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 12px;
  border-bottom: 1px solid #ebebeb;
  flex-shrink: 0;
}

.tab-item {
  padding: 8px 16px;
  font-size: 14px;
  color: #595959;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.15s;
}

.tab-item:hover {
  color: #262626;
}

.tab-item.active {
  color: #ffa116;
  border-bottom-color: #ffa116;
  font-weight: 500;
}

.split-layout {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  height: 100%;
}

.left-panel {
  background: #fff;
  border-radius: 8px;
  padding: 20px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.right-panel {
  background: #fff;
  border-radius: 8px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
  box-sizing: border-box;
  overflow: hidden;
}

.question-title {
  font-size: 20px;
  font-weight: 600;
  color: #262626;
  margin: 0 0 8px;
}

.question-meta {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.meta-item {
  font-size: 13px;
  color: #8c8c8c;
}

.question-content {
  flex: 1;
  border-top: 1px solid #ebebeb;
  padding-top: 12px;
}

.judge-config {
  display: flex;
  gap: 16px;
  font-size: 13px;
  color: #8c8c8c;
  border-top: 1px solid #ebebeb;
  padding-top: 12px;
}

.recent-submits {
  border-top: 1px solid #ebebeb;
  padding-top: 12px;
}

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: #262626;
  margin-bottom: 8px;
}

.empty-hint {
  font-size: 13px;
  color: #8c8c8c;
}

.submit-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
  font-size: 13px;
  border-bottom: 1px solid #f5f5f5;
}

.submit-lang {
  color: #595959;
}

.submit-meta {
  color: #8c8c8c;
}

.editor-toolbar {
  display: flex;
  align-items: center;
}

.editor-wrapper {
  flex: 1;
  min-height: 0;
  border: 1px solid #ebebeb;
  border-radius: 6px;
}

.submit-area {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex-shrink: 0;
}

.judge-result {
  flex-shrink: 0;
  border-top: 1px solid #ebebeb;
  padding-top: 12px;
}

.rate-limit-tip {
  padding: 8px 12px;
  background: #fff2f0;
  border: 1px solid #ffccc7;
  border-radius: 6px;
}

.rate-limit-main {
  font-size: 13px;
  color: #cf1322;
}

.rate-limit-sub {
  font-size: 12px;
  color: #ff7875;
  margin-top: 4px;
}

.polling {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #595959;
}

.result-status {
  font-size: 20px;
  font-weight: 700;
  margin-bottom: 8px;
}

.result-meta {
  display: flex;
  gap: 16px;
  font-size: 13px;
  color: #595959;
}
</style>
