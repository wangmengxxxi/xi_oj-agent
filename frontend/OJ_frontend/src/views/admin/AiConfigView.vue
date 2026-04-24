<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Message, Modal } from '@arco-design/web-vue'
import { getAiConfig, updateAiConfig, rebuildQuestionVectors, importKnowledge } from '@/api/aiConfig'

const router = useRouter()
const loading = ref(false)
const saving = ref(false)
const rebuilding = ref(false)
const importing = ref(false)
const importResult = ref('')

const form = reactive({
  'ai.global.enable': 'false',
  'ai.model.name': '',
  'ai.model.base_url': '',
  'ai.model.embedding_name': '',
  'ai.rag.top_k': '',
  'ai.rag.similarity_threshold': '',
  'ai.prompt.chat_system': '',
  'ai.prompt.code_analysis': '',
  'ai.prompt.wrong_analysis': '',
  'ai.prompt.question_parse': '',
})

type FormKey = keyof typeof form

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

const MODEL_OPTIONS = [
  { label: 'qwen-turbo', value: 'qwen-turbo' },
  { label: 'qwen-plus', value: 'qwen-plus' },
  { label: 'qwen-max', value: 'qwen-max' },
]

const EMBEDDING_OPTIONS = [
  { label: 'text-embedding-v3', value: 'text-embedding-v3' },
  { label: 'text-embedding-v2', value: 'text-embedding-v2' },
]

async function handleKnowledgeImport(_fileList: any[], fileItem: any) {
  const file = fileItem.file as File
  if (!file) return
  importing.value = true
  importResult.value = ''
  try {
    const res = await importKnowledge(file)
    importResult.value = res.data.data ?? '导入完成'
    Message.success(importResult.value)
  } catch (err: any) {
    importResult.value = ''
    Message.error(err?.message || '知识库导入失败')
  } finally {
    importing.value = false
  }
}

onMounted(loadConfig)
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

        <!-- 模型配置 -->
        <div class="config-section">
          <div class="section-title">大模型配置</div>
          <a-form-item label="聊天模型名称">
            <a-select v-model="form['ai.model.name']" allow-search style="width: 100%">
              <a-option
                v-for="opt in MODEL_OPTIONS"
                :key="opt.value"
                :value="opt.value"
              >
                {{ opt.label }}
              </a-option>
            </a-select>
          </a-form-item>
          <a-form-item label="嵌入模型名称">
            <a-select v-model="form['ai.model.embedding_name']" allow-search style="width: 100%">
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
          <a-form-item label="API 端点">
            <a-input v-model="form['ai.model.base_url']" placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1" />
          </a-form-item>
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
        </div>

        <!-- Prompt 配置 -->
        <div class="config-section">
          <div class="section-title">Prompt 配置</div>
          <a-form-item label="AI 对话系统 Prompt">
            <a-textarea v-model="form['ai.prompt.chat_system']" :auto-size="{ minRows: 4, maxRows: 12 }" placeholder="AI 编程助手的系统指令，留空则使用默认 Prompt" />
            <div class="field-hint">控制 AI 编程助手的行为和回答风格</div>
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
        上传 Markdown 文件（.md），系统将自动解析内容并存入向量知识库，用于 RAG 检索增强。
      </div>
      <div class="knowledge-upload">
        <a-upload
          :auto-upload="false"
          accept=".md,.markdown"
          :limit="1"
          :show-file-list="false"
          @change="handleKnowledgeImport"
        >
          <template #upload-button>
            <a-button :loading="importing">
              {{ importing ? '导入中...' : '选择文件并导入' }}
            </a-button>
          </template>
        </a-upload>
        <div v-if="importResult" class="import-result">{{ importResult }}</div>
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
}

.import-result {
  font-size: 13px;
  color: #00b42a;
  font-weight: 500;
}
</style>
