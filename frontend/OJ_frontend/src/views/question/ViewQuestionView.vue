<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import { getQuestionVOById } from '@/api/question'
import { doQuestionSubmit, listQuestionSubmitByPage } from '@/api/questionSubmit'
import type { QuestionVO, QuestionSubmitVO, JudgeInfo } from '@/types'
import MdViewer from '@/components/MdViewer.vue'
import CodeEditor from '@/components/CodeEditor.vue'

const route = useRoute()
const router = useRouter()
const questionId = Number(route.params.id)

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
  java: `import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        // 在此编写代码
    }
}`,
  cpp: `#include <bits/stdc++.h>
using namespace std;

int main() {
    ios::sync_with_stdio(false);
    cin.tie(nullptr);
    // 在此编写代码
    return 0;
}`,
  go: `package main

import "fmt"

func main() {
    // 在此编写代码
    fmt.Println()
}`,
}

const language = ref('java')
const code = ref(CODE_TEMPLATES.java)

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
      rateLimitMsg.value = err.message ?? '提交频率过快，请稍后再试'
      judgeResult.value = { status: 'idle', submitVO: null }
      // 解析冷却秒数（message 中含"xxx秒"）
      const match = rateLimitMsg.value.match(/(\d+)\s*秒/)
      if (match) {
        const secs = parseInt(match[1])
        rateLimitUntil.value = Date.now() + secs * 1000
        startCooldown(secs)
      } else {
        // 日级别限流，不设倒计时
        rateLimitUntil.value = Date.now() + 86400 * 1000
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
          <!-- 题目标题和标签 -->
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

          <!-- 题目描述 -->
          <div class="question-content">
            <MdViewer :content="question.content" />
          </div>

          <!-- 时间/内存限制 -->
          <div class="judge-config">
            <span>时间限制：{{ question.judgeConfig?.timeLimit ?? '-' }} ms</span>
            <span>内存限制：{{ (question.judgeConfig?.memoryLimit ?? 0) / 1024 }} MB</span>
          </div>

          <!-- 最近提交记录 -->
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
              ⚠️ {{ rateLimitMsg }}
              <span v-if="cooldownSeconds > 0">（{{ cooldownSeconds }}s 后恢复）</span>
            </div>
            <a-button
              type="primary"
              size="large"
              :loading="submitting"
              :disabled="submitDisabled"
              @click="handleSubmit"
            >
              {{ submitting ? '提交中...' : submitDisabled && cooldownSeconds > 0 ? `等待 ${cooldownSeconds}s` : '提交代码' }}
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
              </div>
            </div>
          </div>
        </div>
      </div>
    </a-spin>
  </div>
</template>

<style scoped>
.view-question-page {
  height: calc(100vh - 104px);
  overflow: hidden;
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
  font-size: 13px;
  color: #ef4743;
  padding: 8px 12px;
  background: #fff2f0;
  border-radius: 6px;
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
