<script setup lang="ts">
import { ref, reactive, onMounted, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import {
  getQuestionVOById,
  getQuestionById,
  addQuestion,
  editQuestion,
} from '@/api/question'
import type { JudgeCase } from '@/types'
import MdEditor from '@/components/MdEditor.vue'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

// 如果 query 中有 id，则为编辑模式
const editId = computed(() => (route.query.id ? Number(route.query.id) : null))
const isEdit = computed(() => editId.value !== null)
const pageTitle = computed(() => (isEdit.value ? '编辑题目' : '创建题目'))

const loading = ref(false)
const submitting = ref(false)

const form = reactive({
  title: '',
  tags: [] as string[],
  content: '',
  answer: '',
  judgeConfig: {
    timeLimit: 1000,
    memoryLimit: 262144,
    stackLimit: 262144,
  },
  judgeCase: [{ input: '', output: '' }] as JudgeCase[],
})

const tagInput = ref('')

function addTag() {
  const tag = tagInput.value.trim()
  if (tag && !form.tags.includes(tag)) {
    form.tags.push(tag)
  }
  tagInput.value = ''
}

function removeTag(tag: string) {
  form.tags = form.tags.filter((t) => t !== tag)
}

function addJudgeCase() {
  form.judgeCase.push({ input: '', output: '' })
}

function removeJudgeCase(index: number) {
  if (form.judgeCase.length > 1) {
    form.judgeCase.splice(index, 1)
  }
}

async function loadQuestion() {
  if (!editId.value) return
  loading.value = true
  try {
    // 管理员用完整接口，普通用户用 VO 接口
    let data
    if (userStore.isAdmin()) {
      const res = await getQuestionById(editId.value)
      data = res.data.data
      if (data) {
        form.answer = data.answer ?? ''
        // 后端返回原始实体，judgeCase/tags/judgeConfig 可能是 JSON 字符串，需解析
        const judgeCase = typeof data.judgeCase === 'string'
          ? JSON.parse(data.judgeCase)
          : data.judgeCase
        form.judgeCase = judgeCase ?? [{ input: '', output: '' }]
        const tags = typeof data.tags === 'string'
          ? JSON.parse(data.tags)
          : data.tags
        form.tags = tags ?? []
        const judgeConfig = typeof data.judgeConfig === 'string'
          ? JSON.parse(data.judgeConfig)
          : data.judgeConfig
        form.judgeConfig = { ...form.judgeConfig, ...judgeConfig }
        form.title = data.title
        form.content = data.content
      }
    } else {
      const res = await getQuestionVOById(editId.value)
      data = res.data.data
      if (data) {
        form.title = data.title
        form.tags = data.tags ?? []
        form.content = data.content
        form.judgeConfig = { ...form.judgeConfig, ...data.judgeConfig }
      }
    }
  } catch (err: any) {
    Message.error(err?.message || '加载题目失败')
    router.back()
  } finally {
    loading.value = false
  }
}

async function handleSubmit() {
  if (!form.title.trim()) {
    Message.warning('请填写题目标题')
    return
  }
  if (!form.content.trim()) {
    Message.warning('请填写题目内容')
    return
  }
  submitting.value = true
  try {
    if (isEdit.value) {
      await editQuestion({
        id: editId.value!,
        title: form.title,
        tags: form.tags,
        content: form.content,
        answer: form.answer,
        judgeConfig: form.judgeConfig,
        judgeCase: form.judgeCase,
      })
      Message.success('更新成功')
    } else {
      await addQuestion({
        title: form.title,
        tags: form.tags,
        content: form.content,
        answer: form.answer,
        judgeConfig: form.judgeConfig,
        judgeCase: form.judgeCase,
      })
      Message.success('创建成功')
    }
    router.push('/questions')
  } catch (err: any) {
    Message.error(err?.message || '操作失败')
  } finally {
    submitting.value = false
  }
}

onMounted(() => loadQuestion())
</script>

<template>
  <div class="add-question-page">
    <a-spin :loading="loading" style="width: 100%">
      <div class="page-header">
        <h2>{{ pageTitle }}</h2>
      </div>

      <a-form :model="form" layout="vertical">
        <!-- 标题 -->
        <a-form-item label="题目标题" required>
          <a-input
            v-model="form.title"
            placeholder="请输入题目标题"
            :max-length="100"
            show-word-limit
          />
        </a-form-item>

        <!-- 标签 -->
        <a-form-item label="题目标签">
          <div class="tag-editor">
            <div class="tag-list">
              <a-tag
                v-for="tag in form.tags"
                :key="tag"
                closable
                @close="removeTag(tag)"
              >
                {{ tag }}
              </a-tag>
            </div>
            <div class="tag-input-row">
              <a-input
                v-model="tagInput"
                placeholder="输入标签后回车添加"
                style="width: 200px"
                @press-enter="addTag"
              />
              <a-button size="small" @click="addTag">+ 添加</a-button>
            </div>
          </div>
        </a-form-item>

        <!-- 题目内容 -->
        <a-form-item label="题目内容（支持 Markdown）" required>
          <MdEditor v-model="form.content" placeholder="请输入题目描述..." height="350px" />
        </a-form-item>

        <!-- 参考答案 -->
        <a-form-item label="参考答案（支持 Markdown）">
          <MdEditor v-model="form.answer" placeholder="请输入参考答案..." height="250px" />
        </a-form-item>

        <!-- 判题配置 -->
        <a-form-item label="判题配置">
          <div class="judge-config-row">
            <a-form-item label="时间限制（ms）" style="flex: 1">
              <a-input-number
                v-model="form.judgeConfig.timeLimit"
                :min="100"
                :max="10000"
                :step="100"
              />
            </a-form-item>
            <a-form-item label="内存限制（KB）" style="flex: 1">
              <a-input-number
                v-model="form.judgeConfig.memoryLimit"
                :min="16384"
                :max="1048576"
                :step="16384"
              />
            </a-form-item>
            <a-form-item label="堆栈限制（KB）" style="flex: 1">
              <a-input-number
                v-model="form.judgeConfig.stackLimit"
                :min="16384"
                :max="1048576"
                :step="16384"
              />
            </a-form-item>
          </div>
        </a-form-item>

        <!-- 判题用例 -->
        <a-form-item label="判题用例">
          <div class="judge-cases">
            <div
              v-for="(tc, index) in form.judgeCase"
              :key="index"
              class="judge-case-row"
            >
              <div class="case-index">用例 {{ index + 1 }}</div>
              <div class="case-inputs">
                <a-form-item label="输入">
                  <a-textarea
                    v-model="tc.input"
                    placeholder="标准输入"
                    :auto-size="{ minRows: 2, maxRows: 6 }"
                  />
                </a-form-item>
                <a-form-item label="输出">
                  <a-textarea
                    v-model="tc.output"
                    placeholder="期望输出"
                    :auto-size="{ minRows: 2, maxRows: 6 }"
                  />
                </a-form-item>
              </div>
              <a-button
                v-if="form.judgeCase.length > 1"
                type="text"
                status="danger"
                size="small"
                @click="removeJudgeCase(index)"
              >
                删除
              </a-button>
            </div>
            <a-button type="dashed" long @click="addJudgeCase">
              + 添加用例
            </a-button>
          </div>
        </a-form-item>

        <!-- 按钮 -->
        <div class="form-actions">
          <a-button @click="router.back()">取消</a-button>
          <a-button type="primary" :loading="submitting" @click="handleSubmit">
            {{ isEdit ? '保存修改' : '提交创建' }}
          </a-button>
        </div>
      </a-form>
    </a-spin>
  </div>
</template>

<style scoped>
.add-question-page {
  background: #fff;
  border-radius: 8px;
  padding: 24px;
  max-width: 960px;
  margin: 0 auto;
}

.page-header h2 {
  font-size: 20px;
  font-weight: 600;
  color: #262626;
  margin-bottom: 24px;
}

.tag-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  min-height: 28px;
}

.tag-input-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.judge-config-row {
  display: flex;
  gap: 24px;
}

.judge-cases {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.judge-case-row {
  border: 1px solid #ebebeb;
  border-radius: 6px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.case-index {
  font-size: 13px;
  font-weight: 600;
  color: #595959;
}

.case-inputs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 24px;
  padding-top: 24px;
  border-top: 1px solid #ebebeb;
}
</style>
