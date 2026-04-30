<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { Message, Modal } from '@arco-design/web-vue'
import {
  getAiConfig,
  updateAiConfig,
  rebuildQuestionVectors,
  importKnowledge,
  getImportStatus,
  testProviderConnection,
} from '@/api/aiConfig'
import { listRateLimitRules, updateRateLimitRule } from '@/api/rateLimit'
import type { RateLimitRule } from '@/api/rateLimit'
import { AI_PROVIDERS } from '@/constants/aiProviders'

const router = useRouter()
const loading = ref(false)
const saving = ref(false)
const rebuilding = ref(false)
const importing = ref(false)
const importResult = ref('')
const importTaskId = ref('')
const importProgress = ref(0)
const importStep = ref('')
const selectedFile = ref<File | null>(null)
const selectedFileName = ref('')
let pollTimer: ReturnType<typeof setInterval> | null = null

// 供应商配置
const providerSaving = ref(false)
const providerTesting = ref(false)
const selectedProvider = ref('dashscope')
const providerApiKey = ref('')
const providerModelName = ref('')
const providerBaseUrl = ref('')
const embeddingApiKey = ref('')

const currentProvider = computed(() =>
  AI_PROVIDERS.find((p) => p.id === selectedProvider.value)
)

const modelOptions = computed(() =>
  currentProvider.value?.models.map((m) => ({ label: m, value: m })) ?? []
)

function onProviderSelect(providerId: string) {
  selectedProvider.value = providerId
  const provider = AI_PROVIDERS.find((p) => p.id === providerId)
  if (provider) {
    providerBaseUrl.value = provider.baseUrl
    providerModelName.value = provider.models[0] ?? ''
  }
  providerApiKey.value = ''
}

async function handleSaveProvider() {
  if (!providerApiKey.value && !providerApiKey.value.startsWith('****')) {
    // 如果是脱敏值（未修改），允许跳过
  }
  providerSaving.value = true
  try {
    await updateAiConfig({ configKey: 'ai.provider', configValue: selectedProvider.value })
    await updateAiConfig({ configKey: 'ai.model.base_url', configValue: providerBaseUrl.value })
    await updateAiConfig({ configKey: 'ai.model.name', configValue: providerModelName.value })
    if (providerApiKey.value && !providerApiKey.value.startsWith('****')) {
      await updateAiConfig({
        configKey: 'ai.provider.api_key_encrypted',
        configValue: providerApiKey.value,
      })
    }
    if (embeddingApiKey.value && !embeddingApiKey.value.startsWith('****')) {
      await updateAiConfig({
        configKey: 'ai.embedding.api_key_encrypted',
        configValue: embeddingApiKey.value,
      })
    }
    Message.success('供应商配置保存成功，模型已热更新')
  } catch (err: any) {
    Message.error(err?.message || '保存失败')
  } finally {
    providerSaving.value = false
  }
}

async function handleTestConnection() {
  const key = providerApiKey.value
  if (!key || key.startsWith('****')) {
    Message.warning('请先输入 API 密钥')
    return
  }
  providerTesting.value = true
  try {
    const res = await testProviderConnection({
      apiKey: key,
      baseUrl: providerBaseUrl.value,
      modelName: providerModelName.value,
    })
    Message.success(res.data.data ?? '连接成功')
  } catch (err: any) {
    Message.error(err?.message || '连接测试失败')
  } finally {
    providerTesting.value = false
  }
}

const form = reactive({
  'ai.global.enable': 'false',
  'ai.model.embedding_name': '',
  'ai.rag.top_k': '' as string | number,
  'ai.rag.similarity_threshold': '' as string | number,
  'ai.rerank.enabled': 'false',
  'ai.rerank.model_name': '',
  'ai.rerank.endpoint': '',
  'ai.rerank.top_n': '' as string | number,
  'ai.agent.mode': 'simple',
  'ai.agent.max_steps': '' as string | number,
  'ai.agent.tool_max_retry': '' as string | number,
  'ai.prompt.chat_system': '',
  'ai.prompt.agent_system': '',
  'ai.prompt.code_analysis': '',
  'ai.prompt.wrong_analysis': '',
  'ai.prompt.question_parse': '',
  'ai.vl.model_name': '',
  'ai.vl.concurrency': '' as string | number,
})

type FormKey = keyof typeof form

// AI 全局令牌桶限流
const TOKEN_BUCKET_KEY = 'ai:global:token_bucket'
const rateLimitLoading = ref(false)
const rateLimitSaving = ref(false)
const tokenBucketRule = reactive({
  limit_count: 20,
  window_seconds: 3,
  is_enable: 1,
})

async function loadTokenBucketRule() {
  rateLimitLoading.value = true
  try {
    const res = await listRateLimitRules()
    const rules: RateLimitRule[] = res.data.data ?? []
    const rule = rules.find((r) => r.rule_key === TOKEN_BUCKET_KEY)
    if (rule) {
      tokenBucketRule.limit_count = rule.limit_count
      tokenBucketRule.window_seconds = rule.window_seconds
      tokenBucketRule.is_enable = rule.is_enable
    }
  } catch (err: any) {
    Message.error(err?.message || '加载限流规则失败')
  } finally {
    rateLimitLoading.value = false
  }
}

async function handleSaveTokenBucket() {
  if (tokenBucketRule.limit_count <= 0) {
    Message.warning('桶容量必须大于 0')
    return
  }
  if (tokenBucketRule.window_seconds <= 0) {
    Message.warning('补充间隔必须大于 0')
    return
  }
  rateLimitSaving.value = true
  try {
    await updateRateLimitRule({
      rule_key: TOKEN_BUCKET_KEY,
      limit_count: tokenBucketRule.limit_count,
      window_seconds: tokenBucketRule.window_seconds,
      is_enable: tokenBucketRule.is_enable,
    })
    Message.success('全局限流配置保存成功，已同步 Redis 缓存')
  } catch (err: any) {
    Message.error(err?.message || '保存失败')
  } finally {
    rateLimitSaving.value = false
  }
}

async function loadConfig() {
  loading.value = true
  try {
    const res = await getAiConfig()
    const data = res.data.data ?? {}
    for (const key of Object.keys(form) as FormKey[]) {
      if (data[key] != null) {
        form[key] = data[key]
      }
    }
    // a-input-number 需要 number 类型才能正确显示
    if (form['ai.rag.top_k']) form['ai.rag.top_k'] = Number(form['ai.rag.top_k'])
    if (form['ai.rag.similarity_threshold']) form['ai.rag.similarity_threshold'] = Number(form['ai.rag.similarity_threshold'])
    if (form['ai.rerank.top_n']) form['ai.rerank.top_n'] = Number(form['ai.rerank.top_n'])
    if (form['ai.agent.max_steps']) form['ai.agent.max_steps'] = Number(form['ai.agent.max_steps'])
    if (form['ai.agent.tool_max_retry']) form['ai.agent.tool_max_retry'] = Number(form['ai.agent.tool_max_retry'])
    if (form['ai.vl.concurrency']) form['ai.vl.concurrency'] = Number(form['ai.vl.concurrency'])
    if (data['ai.provider']) selectedProvider.value = data['ai.provider']
    if (data['ai.model.name']) providerModelName.value = data['ai.model.name']
    if (data['ai.model.base_url']) providerBaseUrl.value = data['ai.model.base_url']
    if (data['ai.provider.api_key_encrypted']) providerApiKey.value = data['ai.provider.api_key_encrypted']
    if (data['ai.embedding.api_key_encrypted']) embeddingApiKey.value = data['ai.embedding.api_key_encrypted']
  } catch (err: any) {
    Message.error(err?.message || '加载配置失败')
  } finally {
    loading.value = false
  }
}

async function handleSave() {
  saving.value = true
  try {
    for (const key of Object.keys(form) as FormKey[]) {
      await updateAiConfig({ configKey: key, configValue: form[key] })
    }
    Message.success('配置保存成功，模型与 RAG 参数即时生效')
  } catch (err: any) {
    Message.error(err?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

function handleRebuild() {
  Modal.confirm({
    title: '确认重建向量',
    content: '将重新向量化所有题目数据，过程可能需要几分钟。确认继续？',
    onOk: async () => {
      rebuilding.value = true
      try {
        const res = await rebuildQuestionVectors()
        Message.success(res.data.data ?? '重建完成')
      } catch (err: any) {
        Message.error(err?.message || '重建失败')
      } finally {
        rebuilding.value = false
      }
    },
  })
}

const EMBEDDING_OPTIONS = [
  { label: 'text-embedding-v3', value: 'text-embedding-v3' },
  { label: 'text-embedding-v2', value: 'text-embedding-v2' },
]

function handleFileSelect(_fileList: any[], fileItem: any) {
  const file = fileItem.file as File
  if (!file) return
  selectedFile.value = file
  selectedFileName.value = file.name
  importResult.value = ''
}

function handleFileRemove() {
  selectedFile.value = null
  selectedFileName.value = ''
  importResult.value = ''
}

async function handleStartImport() {
  if (!selectedFile.value) {
    Message.warning('请先选择文件')
    return
  }
  importing.value = true
  importResult.value = ''
  importTaskId.value = ''
  importProgress.value = 0
  importStep.value = ''
  try {
    const res = await importKnowledge(selectedFile.value)
    const msg = res.data.data ?? '导入完成'
    const ext = selectedFileName.value.split('.').pop()?.toLowerCase()
    if (ext === 'pdf' || ext === 'docx') {
      importTaskId.value = msg
      importResult.value = '已提交导入任务，正在处理中...'
      startPolling(msg)
    } else {
      importResult.value = msg
      Message.success(msg)
      selectedFile.value = null
      selectedFileName.value = ''
      importing.value = false
    }
  } catch (err: any) {
    importResult.value = ''
    Message.error(err?.message || '知识库导入失败')
    importing.value = false
  }
}

function startPolling(taskId: string) {
  stopPolling()
  pollTimer = setInterval(async () => {
    try {
      const res = await getImportStatus(taskId)
      const status = res.data.data
      if (!status) return
      importProgress.value = status.progress ?? 0
      importStep.value = status.currentStep ?? ''
      if (status.status === 'completed') {
        importResult.value = status.message || '导入完成'
        importProgress.value = 100
        Message.success(importResult.value)
        stopPolling()
        importing.value = false
        selectedFile.value = null
        selectedFileName.value = ''
      } else if (status.status === 'failed') {
        importResult.value = status.message || '导入失败'
        importProgress.value = 0
        Message.error(importResult.value)
        stopPolling()
        importing.value = false
      } else {
        importResult.value = status.currentStep
          ? `${status.currentStep}...`
          : '正在处理中...'
      }
    } catch {
      // 轮询失败不中断
    }
  }, 2000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

onMounted(() => {
  loadConfig()
  loadTokenBucketRule()
})

onUnmounted(() => {
  stopPolling()
})
</script>

<template>
  <div class="ai-config-page">
    <div class="page-header">
      <div class="page-title">
        <span class="title-text">AI 系统配置</span>
        <span class="title-sub">管理 AI 模型、RAG 参数和 Prompt 配置</span>
      </div>
      <a-button :loading="loading" @click="loadConfig">刷新</a-button>
    </div>

    <a-spin :loading="loading">
      <a-form :model="form" layout="vertical" class="config-form">
        <!-- 全局开关 -->
        <div class="config-section">
          <div class="section-title">全局开关</div>
          <a-form-item label="AI 功能全局开关">
            <a-switch
              :model-value="form['ai.global.enable'] === 'true'"
              checked-color="#00b42a"
              @change="(v: boolean) => form['ai.global.enable'] = v ? 'true' : 'false'"
            />
            <span class="switch-label">
              {{ form['ai.global.enable'] === 'true' ? '已开启' : '已关闭' }}
            </span>
          </a-form-item>
        </div>

        <!-- AI 全局限流（令牌桶） -->
        <div class="config-section">
          <div class="section-title">AI 全局限流</div>
          <div class="field-hint" style="margin-bottom: 12px">
            基于令牌桶算法，控制所有用户 AI 请求的总速率。修改后即时生效。
          </div>
          <a-spin :loading="rateLimitLoading">
            <a-form-item label="启用全局限流">
              <a-switch
                v-model="tokenBucketRule.is_enable"
                :checked-value="1"
                :unchecked-value="0"
                checked-color="#00b42a"
              />
              <span class="switch-label">
                {{ tokenBucketRule.is_enable === 1 ? '已启用' : '已禁用' }}
              </span>
            </a-form-item>
            <div class="rag-row">
              <a-form-item label="桶容量（最大突发量）">
                <a-input-number v-model="tokenBucketRule.limit_count" :min="1" :max="1000" style="width: 100%">
                  <template #suffix>个</template>
                </a-input-number>
                <div class="field-hint">令牌桶最多容纳的令牌数，决定允许的最大突发请求量</div>
              </a-form-item>
              <a-form-item label="补充间隔">
                <a-input-number v-model="tokenBucketRule.window_seconds" :min="1" :max="3600" style="width: 100%">
                  <template #suffix>秒/个</template>
                </a-input-number>
                <div class="field-hint">每隔多少秒补充 1 个令牌，间隔越小速率越高</div>
              </a-form-item>
            </div>
            <div class="field-hint" style="margin-top: 4px; color: #165dff">
              当前等效速率：约 {{ tokenBucketRule.window_seconds > 0 ? Math.round(60 / tokenBucketRule.window_seconds) : '—' }} 次/分钟，最大突发 {{ tokenBucketRule.limit_count }} 次
            </div>
            <div style="margin-top: 12px">
              <a-button type="primary" size="small" :loading="rateLimitSaving" @click="handleSaveTokenBucket">
                保存限流配置
              </a-button>
            </div>
          </a-spin>
        </div>

        <!-- AI 供应商配置 -->
        <div class="config-section">
          <div class="section-title">AI 供应商配置</div>
          <div class="provider-grid">
            <div
              v-for="provider in AI_PROVIDERS"
              :key="provider.id"
              class="provider-card"
              :class="{ active: selectedProvider === provider.id }"
              @click="onProviderSelect(provider.id)"
            >
              <div class="provider-logo" :style="{ background: provider.color }">
                {{ provider.initial }}
              </div>
              <div class="provider-name">{{ provider.name }}</div>
            </div>
          </div>

          <a-form-item label="API 密钥" style="margin-top: 16px">
            <div class="api-key-row">
              <a-input-password
                v-model="providerApiKey"
                placeholder="输入该供应商的 API Key"
                allow-clear
                style="flex: 1"
              />
              <a-button
                :loading="providerTesting"
                @click="handleTestConnection"
              >
                测试连接
              </a-button>
            </div>
          </a-form-item>

          <a-form-item label="聊天模型">
            <a-select
              v-model="providerModelName"
              allow-create
              allow-search
              placeholder="选择或输入模型名称"
              style="width: 100%"
            >
              <a-option
                v-for="opt in modelOptions"
                :key="opt.value"
                :value="opt.value"
              >
                {{ opt.label }}
              </a-option>
            </a-select>
            <div class="field-hint">可从预设列表选择，也可直接输入自定义模型名</div>
          </a-form-item>

          <a-form-item label="API 端点">
            <a-input v-model="providerBaseUrl" placeholder="选择供应商后自动填充" />
            <div class="field-hint">选择供应商后自动填充，如有自定义端点可手动修改</div>
          </a-form-item>

          <div class="embed-section">
            <div class="embed-title">嵌入模型配置</div>
            <a-form-item label="嵌入模型名称">
              <a-select v-model="form['ai.model.embedding_name']" allow-search allow-create style="width: 100%">
                <a-option
                  v-for="opt in EMBEDDING_OPTIONS"
                  :key="opt.value"
                  :value="opt.value"
                >
                  {{ opt.label }}
                </a-option>
              </a-select>
              <div class="field-hint">修改嵌入模型后需重新向量化全量数据</div>
            </a-form-item>
            <a-form-item label="嵌入模型 API 密钥（可选）">
              <a-input-password
                v-model="embeddingApiKey"
                placeholder="留空则使用聊天模型的 API 密钥"
                allow-clear
              />
              <div class="field-hint">如果嵌入模型使用不同供应商，在此输入对应密钥</div>
            </a-form-item>
          </div>

          <div style="margin-top: 16px">
            <a-button type="primary" :loading="providerSaving" @click="handleSaveProvider">
              保存供应商配置
            </a-button>
          </div>
        </div>

        <!-- RAG 配置 -->
        <div class="config-section">
          <div class="section-title">RAG 检索配置</div>
          <div class="rag-row">
            <a-form-item label="检索条数 (topK)">
              <a-input-number v-model="form['ai.rag.top_k']" :min="1" :max="20" style="width: 100%" />
            </a-form-item>
            <a-form-item label="相似度阈值">
              <a-input-number v-model="form['ai.rag.similarity_threshold']" :min="0" :max="1" :step="0.05" style="width: 100%" />
            </a-form-item>
          </div>

          <div class="rerank-section">
            <div class="rerank-title">Rerank 重排序</div>
            <div class="field-hint" style="margin-bottom: 12px">
              开启后，检索结果将经过 Rerank 模型重排序，提升相关性。使用聊天模型的 API 密钥进行认证。
            </div>
            <a-form-item label="启用 Rerank">
              <a-switch
                :model-value="form['ai.rerank.enabled'] === 'true'"
                @change="(val: boolean) => form['ai.rerank.enabled'] = val ? 'true' : 'false'"
              />
            </a-form-item>
            <div class="rag-row">
              <a-form-item label="Rerank 模型名称">
                <a-input
                  v-model="form['ai.rerank.model_name']"
                  placeholder="gte-rerank"
                  :disabled="form['ai.rerank.enabled'] !== 'true'"
                />
              </a-form-item>
              <a-form-item label="Rerank 保留条数">
                <a-input-number
                  v-model="form['ai.rerank.top_n']"
                  :min="1"
                  :max="20"
                  style="width: 100%"
                  :disabled="form['ai.rerank.enabled'] !== 'true'"
                />
              </a-form-item>
            </div>
            <a-form-item label="Rerank API 端点">
              <a-input
                v-model="form['ai.rerank.endpoint']"
                placeholder="默认使用 DashScope Rerank 端点"
                :disabled="form['ai.rerank.enabled'] !== 'true'"
              />
              <div class="field-hint">留空则使用默认 DashScope 端点，如使用其他兼容服务可自定义</div>
            </a-form-item>
          </div>
        </div>

        <!-- Agent 配置 -->
        <div class="config-section">
          <div class="section-title">VL 视觉模型配置</div>
          <div class="field-hint" style="margin-bottom: 12px">
            导入 PDF/Word 时，使用视觉语言模型自动为图片生成描述，提升图片检索相关性。使用聊天模型的 API 密钥和端点。
          </div>
          <div class="rag-row">
            <a-form-item label="VL 模型名称">
              <a-select
                v-model="form['ai.vl.model_name']"
                allow-create
                allow-search
                placeholder="选择或输入 VL 模型名称"
                style="width: 100%"
              >
                <a-option value="qwen-vl-plus">qwen-vl-plus</a-option>
                <a-option value="qwen-vl-max">qwen-vl-max</a-option>
                <a-option value="qwen2.5-vl-72b-instruct">qwen2.5-vl-72b-instruct</a-option>
              </a-select>
              <div class="field-hint">推荐 qwen-vl-plus，性价比最高；qwen-vl-max 效果更好但更贵</div>
            </a-form-item>
            <a-form-item label="并发调用线程数">
              <a-input-number
                v-model="form['ai.vl.concurrency']"
                :min="1"
                :max="16"
                style="width: 100%"
              />
              <div class="field-hint">图片描述生成的并发数，建议 2-8，过高可能触发 API 限流</div>
            </a-form-item>
          </div>
        </div>

        <!-- Agent 推理配置 -->
        <div class="config-section">
          <div class="section-title">Agent 推理配置</div>
          <a-form-item label="推理模式">
            <a-select v-model="form['ai.agent.mode']" style="width: 100%">
              <a-option value="simple">标准模式（LangChain4j AiServices）</a-option>
              <a-option value="advanced">高级模式（自定义 ReAct 推理链）</a-option>
            </a-select>
            <div class="field-hint">标准模式由框架自动管理工具调用，高级模式支持逐步推理追踪和自定义控制</div>
          </a-form-item>
          <div class="rag-row">
            <a-form-item label="最大推理步数">
              <a-input-number
                v-model="form['ai.agent.max_steps']"
                :min="1"
                :max="20"
                style="width: 100%"
                :disabled="form['ai.agent.mode'] !== 'advanced'"
              />
              <div class="field-hint">高级模式下 Agent 单次对话最多执行的推理步数</div>
            </a-form-item>
            <a-form-item label="工具调用重试次数">
              <a-input-number
                v-model="form['ai.agent.tool_max_retry']"
                :min="0"
                :max="5"
                style="width: 100%"
                :disabled="form['ai.agent.mode'] !== 'advanced'"
              />
              <div class="field-hint">高级模式下单个工具调用失败后的最大重试次数</div>
            </a-form-item>
          </div>
        </div>

        <!-- Prompt 配置 -->
        <div class="config-section">
          <div class="section-title">Prompt 配置</div>
          <a-form-item label="AI 对话系统 Prompt（标准模式）">
            <a-textarea v-model="form['ai.prompt.chat_system']" :auto-size="{ minRows: 4, maxRows: 12 }" placeholder="AI 编程助手的系统指令，留空则使用默认 Prompt" />
            <div class="field-hint">标准模式（AiServices）下 AI 编程助手的行为和回答风格</div>
          </a-form-item>
          <a-form-item label="AI 对话系统 Prompt（高级模式）">
            <a-textarea v-model="form['ai.prompt.agent_system']" :auto-size="{ minRows: 4, maxRows: 12 }" placeholder="高级模式 ReAct 推理的系统指令，留空则使用默认 Prompt。注意：必须保留 Thought/Action/Answer 格式约束" />
            <div class="field-hint">高级模式（ReAct 推理链）下的系统指令，包含工具列表和输出格式约束，修改时请勿删除格式规则</div>
          </a-form-item>
          <a-form-item label="代码分析 Prompt">
            <a-textarea v-model="form['ai.prompt.code_analysis']" :auto-size="{ minRows: 3, maxRows: 8 }" />
          </a-form-item>
          <a-form-item label="错题分析 Prompt">
            <a-textarea v-model="form['ai.prompt.wrong_analysis']" :auto-size="{ minRows: 3, maxRows: 8 }" />
          </a-form-item>
          <a-form-item label="题目解析 Prompt">
            <a-textarea v-model="form['ai.prompt.question_parse']" :auto-size="{ minRows: 3, maxRows: 8 }" />
          </a-form-item>
        </div>

        <!-- 操作区 -->
        <div class="action-bar">
          <a-button type="primary" :loading="saving" @click="handleSave">保存配置</a-button>
          <a-button :loading="rebuilding" @click="handleRebuild">重建题目向量</a-button>
        </div>
      </a-form>
    </a-spin>

    <!-- 知识库管理 -->
    <div class="config-section">
      <div class="section-title">知识库管理</div>
      <div class="knowledge-desc">
        上传知识文件（支持 .md / .pdf / .docx），系统将自动解析内容并存入向量知识库，用于 RAG 检索增强。
      </div>
      <div class="knowledge-upload">
        <a-upload
          :auto-upload="false"
          accept=".md,.markdown,.pdf,.docx"
          :limit="1"
          :show-file-list="false"
          @change="handleFileSelect"
        >
          <template #upload-button>
            <a-button :disabled="importing">选择文件</a-button>
          </template>
        </a-upload>
        <div v-if="selectedFileName" class="selected-file">
          <span class="file-name">{{ selectedFileName }}</span>
          <a-button size="mini" type="text" status="danger" :disabled="importing" @click="handleFileRemove">删除</a-button>
          <a-button type="primary" size="small" :loading="importing" @click="handleStartImport">
            {{ importing ? '导入中...' : '开始导入' }}
          </a-button>
        </div>
        <div v-if="importing && importTaskId" class="import-progress">
          <a-progress :percent="importProgress / 100" :stroke-width="8" />
          <span class="progress-step">{{ importStep }}</span>
        </div>
        <div v-if="importResult && !importing" class="import-result">{{ importResult }}</div>
      </div>
    </div>

    <div class="nav-link-bar">
      <a class="nav-link" @click="router.push('/manage/rate-limit')">→ 前往限流规则管理</a>
    </div>
  </div>
</template>

<style scoped>
.ai-config-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

.page-title {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.title-text {
  font-size: 18px;
  font-weight: 600;
  color: #262626;
}

.title-sub {
  font-size: 13px;
  color: #8c8c8c;
}

.config-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.config-section {
  background: #fff;
  border-radius: 8px;
  padding: 20px;
}

.section-title {
  font-size: 15px;
  font-weight: 600;
  color: #262626;
  margin-bottom: 16px;
  padding-bottom: 8px;
  border-bottom: 1px solid #f0f0f0;
}

.rag-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.switch-label {
  margin-left: 8px;
  font-size: 13px;
  color: #595959;
}

.field-hint {
  margin-top: 4px;
  font-size: 12px;
  color: #8c8c8c;
}

.action-bar {
  display: flex;
  gap: 12px;
}

.provider-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(110px, 1fr));
  gap: 10px;
}

.provider-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 12px 8px;
  border: 2px solid #f0f0f0;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
}

.provider-card:hover {
  border-color: #c0c0c0;
}

.provider-card.active {
  border-color: #165dff;
  background: #f2f3ff;
}

.provider-logo {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-weight: 700;
  font-size: 14px;
}

.provider-name {
  font-size: 12px;
  color: #333;
  text-align: center;
}

.api-key-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.embed-section {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px dashed #e8e8e8;
}

.embed-title {
  font-size: 13px;
  font-weight: 600;
  color: #595959;
  margin-bottom: 12px;
}

.rerank-section {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px dashed #e8e8e8;
}

.rerank-title {
  font-size: 13px;
  font-weight: 600;
  color: #595959;
  margin-bottom: 4px;
}

.nav-link-bar {
  padding: 12px 0;
}

.nav-link {
  color: #165dff;
  font-size: 13px;
  cursor: pointer;
}

.nav-link:hover {
  text-decoration: underline;
}

.knowledge-desc {
  font-size: 13px;
  color: #8c8c8c;
  margin-bottom: 12px;
  line-height: 1.6;
}

.knowledge-upload {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.selected-file {
  display: flex;
  align-items: center;
  gap: 8px;
}

.file-name {
  font-size: 13px;
  color: #333;
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.import-result {
  font-size: 13px;
  color: #00b42a;
  font-weight: 500;
}

.import-progress {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 8px;
}

.progress-step {
  font-size: 12px;
  color: #8c8c8c;
}
</style>
