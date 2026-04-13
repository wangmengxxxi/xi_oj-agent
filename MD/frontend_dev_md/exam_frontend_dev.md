# XI OJ 考试功能 — 前端开发文档

> 版本：V1.0 | 日期：2026-04-09
> 风格参考：与现有页面保持一致（LeetCode 风格 / Arco Design Vue）
> 技术栈：Vue 3 Beta + TypeScript + Arco Design Vue 2.57 + Pinia 3 + Vue Router 5

---

## 一、功能概述

在现有 OJ 体系上新增**考试模块**，入口集成在顶部导航栏。

| 角色 | 功能入口 |
|------|---------|
| 普通用户 | 查看考试列表 → 报名 → 进入考场做题 → 查看个人结果/排行榜 |
| 管理员 | 管理员菜单中新增「管理考试」：创建/编辑/删除考试、添加题目、查看全量结果 |

---

## 二、色彩与视觉规范

**严格继承已有设计规范（`前端方案设计.md` 第二章），新增考试专用色：**

| 色彩用途 | 色值 | 说明 |
|---------|------|------|
| 品牌主色 | `#FFA116` | 与现有一致，用于考试徽章、进行中状态 |
| 考试状态 — 未开始 | `#8C8C8C` | 灰色，与「等待中」一致 |
| 考试状态 — 进行中 | `#FFA116` | 橙黄色，品牌色 |
| 考试状态 — 已结束 | `#00AF9B` | 绿色，与「通过」一致 |
| 考试状态 — 已取消 | `#EF4743` | 红色，与「错误」一致 |
| 倒计时 — 充裕（>30min） | `#262626` | 正文主色 |
| 倒计时 — 紧张（10~30min） | `#F0AD4E` | 黄色警告 |
| 倒计时 — 危险（<10min） | `#EF4743` | 红色紧急 |

---

## 三、页面结构与路由

### 3.1 新增路由一览

| 路径 | 组件 | 权限 | 导航显示 |
|------|------|------|---------|
| `/exams` | `ExamListView.vue` | 需登录 | 是（「参加考试」，在「提交记录」之后） |
| `/exam/:id` | `ExamDetailView.vue` | 需登录 | 否 |
| `/exam/:examId/problem/:questionId` | `ExamRoomView.vue` | 需登录 | 否 |
| `/exam/:id/result` | `ExamResultView.vue` | 需登录 | 否 |
| `/manage/exam` | `ManageExamView.vue` | admin | 是（「管理考试」，在「管理题目」之后） |
| `/manage/exam/add` | `AddExamView.vue` | admin | 否 |
| `/manage/exam/edit` | `AddExamView.vue`（?id=xxx） | admin | 否 |

### 3.2 路由配置（追加到现有 `routes.ts`）

```typescript
// 追加到 BasicLayout 的 children 数组中
{
  path: '/exams',
  name: 'ExamList',
  component: () => import('@/views/exam/ExamListView.vue'),
  meta: { requiresRole: 'user', title: '参加考试', showInMenu: true }
},
{
  path: '/exam/:id',
  name: 'ExamDetail',
  component: () => import('@/views/exam/ExamDetailView.vue'),
  meta: { requiresRole: 'user' }
},
{
  path: '/exam/:examId/problem/:questionId',
  name: 'ExamRoom',
  component: () => import('@/views/exam/ExamRoomView.vue'),
  meta: { requiresRole: 'user' }
},
{
  path: '/exam/:id/result',
  name: 'ExamResult',
  component: () => import('@/views/exam/ExamResultView.vue'),
  meta: { requiresRole: 'user' }
},
{
  path: '/manage/exam',
  name: 'ManageExam',
  component: () => import('@/views/exam/ManageExamView.vue'),
  meta: { requiresRole: 'admin', title: '管理考试', showInMenu: true }
},
{
  path: '/manage/exam/add',
  name: 'AddExam',
  component: () => import('@/views/exam/AddExamView.vue'),
  meta: { requiresRole: 'admin' }
},
{
  path: '/manage/exam/edit',
  name: 'EditExam',
  component: () => import('@/views/exam/AddExamView.vue'),
  meta: { requiresRole: 'admin' }
}
```

### 3.3 顶部导航栏变更

在现有导航项「提交记录」之后插入「参加考试」；管理员菜单增加「管理考试」。

```
[ Logo XI OJ ]  [ 浏览题目 ]  [ 提交记录 ]  [ 参加考试 ]  [ 创建题目（登录后）]  [ 头像 ▾ ]
                                                                  ↓（管理员下拉/额外菜单）
                                                            [ 管理题目 ] [ 管理用户 ] [ 管理考试 ]
```

---

## 四、文件目录结构

```
frontend/src/
├── views/
│   └── exam/
│       ├── ExamListView.vue       # 考试列表页
│       ├── ExamDetailView.vue     # 考试详情/报名页
│       ├── ExamRoomView.vue       # 考场做题页
│       ├── ExamResultView.vue     # 考试结果/排行榜页
│       ├── ManageExamView.vue     # 管理员 - 考试管理页
│       └── AddExamView.vue        # 管理员 - 创建/编辑考试页
├── components/
│   └── ExamCountdown.vue          # 考场倒计时组件（新增）
├── types/
│   └── exam.ts                    # 考试相关类型定义（新增）
└── api/
    └── exam.ts                    # 考试相关接口（新增）
```

---

## 五、类型定义（`src/types/exam.ts`）

```typescript
// 考试状态枚举
export enum ExamStatus {
  NOT_STARTED = 0,
  IN_PROGRESS = 1,
  ENDED = 2,
  CANCELLED = 3
}

export const ExamStatusLabel: Record<ExamStatus, string> = {
  [ExamStatus.NOT_STARTED]: '未开始',
  [ExamStatus.IN_PROGRESS]: '进行中',
  [ExamStatus.ENDED]: '已结束',
  [ExamStatus.CANCELLED]: '已取消'
}

export const ExamStatusColor: Record<ExamStatus, string> = {
  [ExamStatus.NOT_STARTED]: '#8C8C8C',
  [ExamStatus.IN_PROGRESS]: '#FFA116',
  [ExamStatus.ENDED]: '#00AF9B',
  [ExamStatus.CANCELLED]: '#EF4743'
}

export interface JudgeConfig {
  timeLimit: number
  memoryLimit: number
  stackLimit: number
}

export interface ExamQuestionVO {
  id: number
  questionId: number
  title: string
  tags: string[]
  submitNum: number
  acceptedNum: number
  score: number
  sortOrder: number
  judgeConfig: JudgeConfig
  myScore?: number
  myAccepted?: boolean
}

export interface ExamVO {
  id: number
  title: string
  description?: string
  startTime: string
  endTime: string
  status: ExamStatus
  isPublic: number
  questionCount: number
  totalScore: number
  participantCount: number
  joined?: boolean
  questions?: ExamQuestionVO[]
  createUserId: number
  createTime: string
}

export interface ExamRecordVO {
  id: number
  examId: number
  userId: number
  userName: string
  userAvatar?: string
  totalScore: number
  finishTime?: string
  rank: number
  questionScores?: QuestionScoreVO[]
}

export interface QuestionScoreVO {
  questionId: number
  score: number
  isAccepted: boolean
}

// 请求体类型
export interface ExamQueryRequest {
  title?: string
  status?: number
  /** 考试 ID，排行榜接口（/exam/rank）使用 */
  examId?: number
  current?: number
  pageSize?: number
}

export interface ExamAddRequest {
  title: string
  description?: string
  startTime: string
  endTime: string
  isPublic?: number
}

export interface ExamUpdateRequest {
  id: number
  title?: string
  description?: string
  startTime?: string
  endTime?: string
  isPublic?: number
  status?: number
}

export interface ExamJoinRequest {
  examId: number
}

export interface ExamCodeSubmitRequest {
  examId: number
  questionId: number
  language: string
  code: string
}

export interface ExamQuestionAddRequest {
  examId: number
  questionId: number
  score?: number
  sortOrder?: number
}
```

---

## 六、接口封装（`src/api/exam.ts`）

```typescript
import request from '@/plugins/request'
import type {
  ExamVO, ExamRecordVO, ExamQueryRequest, ExamAddRequest,
  ExamUpdateRequest, ExamJoinRequest, ExamCodeSubmitRequest,
  ExamQuestionAddRequest
} from '@/types/exam'
import type { PageResult } from '@/types'

// ===== 用户端 =====

/** 分页获取考试列表 */
export const listExamVOByPage = (data: ExamQueryRequest) =>
  request.post<PageResult<ExamVO>>('/exam/list/page/vo', data)

/** 获取考试详情（含题目列表，进行中/已结束才返回题目） */
export const getExamVO = (id: number) =>
  request.get<ExamVO>('/exam/get/vo', { params: { id } })

/** 报名考试 */
export const joinExam = (data: ExamJoinRequest) =>
  request.post<number>('/exam/join', data)

/** 考场内提交代码，返回 questionSubmitId */
export const submitInExam = (data: ExamCodeSubmitRequest) =>
  request.post<number>('/exam/submit', data)

/** 获取当前用户参赛记录 */
export const getMyExamRecord = (examId: number) =>
  request.get('/exam/my/record', { params: { examId } })

/** 获取考试排行榜 */
export const getExamRank = (examId: number, current = 1, pageSize = 50) =>
  request.post<PageResult<ExamRecordVO>>('/exam/rank', { examId, current, pageSize })

// ===== 管理员端 =====

export const addExam = (data: ExamAddRequest) =>
  request.post<number>('/exam/add', data)

export const updateExam = (data: ExamUpdateRequest) =>
  request.post<boolean>('/exam/update', data)

export const deleteExam = (id: number) =>
  request.post<boolean>('/exam/delete', { id })

export const listExamByPage = (data: ExamQueryRequest) =>
  request.post<PageResult<ExamVO>>('/exam/list/page', data)

export const addExamQuestion = (data: ExamQuestionAddRequest) =>
  request.post<boolean>('/exam/question/add', data)

export const deleteExamQuestion = (examId: number, questionId: number) =>
  request.post<boolean>('/exam/question/delete', { examId, questionId })

export const getExamResultList = (examId: number, current = 1, pageSize = 50) =>
  request.get<PageResult<ExamRecordVO>>('/exam/result/list', {
    params: { examId, current, pageSize }
  })
```

---

## 七、各页面详细设计

---

### 7.1 考试列表页 `/exams`（`ExamListView.vue`）

**风格参考**：与题目列表页（`/questions`）完全一致，同样为卡片+表格形式。

#### 布局

```
┌──────────────────────────────────────────────────────────────┐
│  搜索框（按考试名称搜索）   状态筛选 [全部 ▾]   [搜索]          │
├────────────────────┬──────────┬──────────┬───────┬───────────┤
│  考试名称           │ 时间       │ 状态      │ 题目数 │  操作      │
├────────────────────┼──────────┼──────────┼───────┼───────────┤
│  2026年春季期中     │ 04-10    │ ● 进行中  │ 5 题  │ [进入考场] │
│  2026年春季期末     │ 06-20    │ ○ 未开始  │ 8 题  │ [查看详情] │
│  2026年寒假选拔赛   │ 01-15    │ ✓ 已结束  │ 10题  │ [查看结果] │
└────────────────────┴──────────┴──────────┴───────┴───────────┘
                              [< 1  2  3 ... >]
```

#### 字段来源

| 展示字段 | 来源 | 说明 |
|---------|------|------|
| 考试名称 | `ExamVO.title` | 点击跳转 `/exam/:id` |
| 时间 | `ExamVO.startTime ~ endTime` | 格式化为 `MM-DD HH:mm` |
| 状态标签 | `ExamVO.status` | 使用 `ExamStatusLabel` + `ExamStatusColor` |
| 题目数 | `ExamVO.questionCount` | |
| 参与人数 | `ExamVO.participantCount` | |
| 操作按钮 | 根据 `status` + `joined` 动态显示 | 见下表 |

**操作按钮逻辑**：

| 条件 | 按钮文字 | 动作 |
|------|---------|------|
| status=0（未开始），joined=false | `报名参加` | 调用 `joinExam` |
| status=0，joined=true | `已报名` | 禁用 |
| status=1（进行中），joined=false | `立即报名` | 调用 `joinExam`，成功后跳转考试详情 |
| status=1，joined=true | `进入考场` | 主色按钮，跳转 `/exam/:id` |
| status=2（已结束） | `查看结果` | 跳转 `/exam/:id/result` |
| status=3（已取消） | `已取消` | 禁用，灰色 |

#### 交互细节

- 状态筛选下拉：`全部 / 未开始 / 进行中 / 已结束`（不含「已取消」，或由管理员页面处理）
- 分页：每页 10 条（与题目列表一致）
- 考试名称点击跳转详情页，操作按钮直接触发对应动作

#### 代码结构参考

```vue
<template>
  <div class="exam-list-view">
    <!-- 搜索栏（与 QuestionsView 搜索栏同样的间距和样式） -->
    <div class="search-bar" style="margin-bottom: 16px; display: flex; gap: 12px;">
      <a-input v-model="searchTitle" placeholder="搜索考试名称" style="width: 280px" allow-clear />
      <a-select v-model="searchStatus" placeholder="全部状态" style="width: 140px" allow-clear>
        <a-option :value="0">未开始</a-option>
        <a-option :value="1">进行中</a-option>
        <a-option :value="2">已结束</a-option>
      </a-select>
      <a-button type="primary" @click="doSearch" style="background: #FFA116; border-color: #FFA116;">
        搜索
      </a-button>
    </div>

    <!-- 考试列表表格 -->
    <a-table :columns="columns" :data="examList" :pagination="false"
             :loading="loading" row-key="id">
      <template #title="{ record }">
        <a-link @click="router.push(`/exam/${record.id}`)">{{ record.title }}</a-link>
      </template>
      <template #time="{ record }">
        <span style="color: #595959; font-size: 13px;">
          {{ formatTime(record.startTime) }} ~ {{ formatTime(record.endTime) }}
        </span>
        <!-- 进行中时额外显示剩余时间 -->
        <div v-if="record.status === 1">
          <ExamCountdown :target-time="record.endTime"
                         style="font-size: 12px;" />
        </div>
      </template>
      <template #status="{ record }">
        <a-tag :color="ExamStatusColor[record.status]">
          {{ ExamStatusLabel[record.status] }}
        </a-tag>
      </template>
      <template #action="{ record }">
        <!-- 动态操作按钮 -->
      </template>
    </a-table>

    <!-- 分页 -->
    <div style="display: flex; justify-content: center; margin-top: 20px;">
      <a-pagination v-model:current="current" :total="total" :page-size="pageSize"
                    @change="loadData" />
    </div>
  </div>
</template>
```

---

### 7.2 考试详情/报名页 `/exam/:id`（`ExamDetailView.vue`）

该页面在考试**开始前**用于展示考试信息和报名，在**考试中/结束后**展示题目列表。

#### 布局（考试未开始）

```
┌────────────────────────────────────────────────────────────┐
│  2026年春季期中考试                      ○ 未开始           │
│  距开始还有：⏱ 01:23:45（使用 ExamCountdown，倒计时至 startTime）│
│  开始时间：2026-04-10 09:00                                 │
│  结束时间：2026-04-10 11:00（共 2 小时）                    │
│  参与人数：32 人   |   总分：500 分   |   题目数：5 道       │
├────────────────────────────────────────────────────────────┤
│  考试说明                                                    │
│  （Markdown 渲染区，使用 MdViewer 组件）                    │
│                                                            │
├────────────────────────────────────────────────────────────┤
│                              [ 立即报名 ]（主色按钮 #FFA116）│
│                          （已报名则显示「进入考场」）          │
└────────────────────────────────────────────────────────────┘
```

> **「未开始」倒计时**：`ExamCountdown` 组件接收 `targetTime` prop，此处传入 `exam.startTime`（倒计时到开考）；考试进行中时传入 `exam.endTime`（倒计时到结束）。倒计时归零时自动重新请求 `getExamVO`，页面切换为「进行中」布局。

#### 布局（考试进行中，已报名进入考场）

```
┌────────────────────────────────────────────────────────────┐
│  2026年春季期中考试           距结束：⏱ 01:23:45 （红色倒计时）│
├────────────────────────────────────────────────────────────┤
│  题目列表                                                    │
│  ┌──────┬──────────────────────┬────────┬──────┬──────────┐│
│  │ 序号  │ 题目标题              │ 满分   │ 得分 │  状态     ││
│  ├──────┼──────────────────────┼────────┼──────┼──────────┤│
│  │  1   │ 两数之和              │ 100   │  100 │ ✅ 已通过 ││
│  │  2   │ 链表反转              │ 100   │   0  │ ○ 未提交  ││
│  │  3   │ 二叉树层序遍历         │ 150   │   -  │ ⏳ 判题中 ││
│  └──────┴──────────────────────┴────────┴──────┴──────────┘│
│  当前得分：100 / 500                                         │
│                                           [ 查看排行榜 ]    │
└────────────────────────────────────────────────────────────┘
```

#### 字段来源

| 展示字段 | 来源 | 说明 |
|---------|------|------|
| 考试基本信息 | `GET /api/exam/get/vo?id=` | `ExamVO` |
| 题目列表 | `ExamVO.questions` | 仅进行中/已结束时返回 |
| 各题得分 | `ExamQuestionVO.myScore` | 后端填充，已报名用户才有 |
| 各题状态 | `ExamQuestionVO.myAccepted` | |
| 当前总分 | `ExamQuestionVO.myScore` 求和（前端计算） | |
| 倒计时 | `ExamVO.endTime - NOW()` | 前端计时，不依赖后端 |

#### 倒计时颜色逻辑

```typescript
const countdown = computed(() => {
  const diff = dayjs(exam.value?.endTime).diff(dayjs(), 'second')
  if (diff <= 0) return { text: '已结束', color: '#8C8C8C' }
  const h = Math.floor(diff / 3600)
  const m = Math.floor((diff % 3600) / 60)
  const s = diff % 60
  const text = `${String(h).padStart(2,'0')}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}`
  const color = diff < 600 ? '#EF4743' : diff < 1800 ? '#F0AD4E' : '#262626'
  return { text, color }
})
```

#### 题目状态显示规则

| 条件 | 显示内容 | 颜色 |
|------|---------|------|
| `myAccepted = true` | `✅ 已通过` | `#00AF9B` |
| `myScore = 0`，有提交中 | `⏳ 判题中` | `#8C8C8C` |
| `myScore = 0`，无提交 | `○ 未提交` | `#8C8C8C` |

> **「判题中」状态策略（按页面分别处理）**：
> - `ExamDetailView`（题目列表页）：每 5s 调用一次 `getExamVO` 整体刷新，简单且无需额外逻辑；
> - `ExamRoomView`（做题页）：用户正在做题，需要即时反馈，复用 `ViewQuestionView` 的精确轮询逻辑（通过 `questionSubmitId` 轮询判题状态）。

#### 点击题目行

跳转 `/exam/:examId/problem/:questionId`（考场做题页）。

---

### 7.3 考场做题页 `/exam/:examId/problem/:questionId`（`ExamRoomView.vue`）

**高度复用现有 `ViewQuestionView.vue` 的布局和代码**，差异点如下：

#### 布局

```
┌──────────────────────────┬────────────────────────────────────┐
│  顶部：考试名称  题目 2/5  │  倒计时：⏱ 01:23:45（右上角固定显示）│
├──────────────────────────┼────────────────────────────────────┤
│  左侧：题目信息面板        │  右侧：代码编辑区                   │
│  （与 ViewQuestionView    │  （与 ViewQuestionView 完全一致）   │
│  完全一致）               │                                    │
│                          │  [ 提交代码 ]（提交到考场接口）       │
│  ┌──────────────────┐    │                                    │
│  │ 本题得分：0/100   │    │  ─────── 提交结果区 ───────────     │
│  │ 本题状态：未提交  │    │  （与 ViewQuestionView 一致）       │
│  └──────────────────┘    │                                    │
│                          │                                    │
│  ← 上一题  下一题 →       │                                    │
└──────────────────────────┴────────────────────────────────────┘
```

#### 与 `ViewQuestionView` 的差异

| 差异点 | `ViewQuestionView` | `ExamRoomView` |
|-------|-------------------|---------------|
| 提交接口 | `POST /api/question_submit/` | `POST /api/exam/submit` |
| 请求参数 | `{ questionId, language, code }` | `{ examId, questionId, language, code }` |
| 判题结果轮询 | 同（复用） | 同（复用）—— 返回的 `questionSubmitId` 相同格式 |
| 顶部信息 | 无 | 显示考试名称、当前题目序号、倒计时 |
| 左侧下方 | 最近5条提交记录 | 本题得分 + 考场内本题提交记录（同逻辑） |
| 题目导航 | 无 | 上一题 / 下一题按钮（跳转同一考试其他题目） |
| 退出按钮 | 无 | 「返回考场」按钮（跳转 `/exam/:id`） |

#### 考场提交与判题轮询（复用已有逻辑）

```typescript
// 提交代码（考场内）
const handleSubmit = async () => {
  try {
    const res = await submitInExam({
      examId: Number(route.params.examId),
      questionId: Number(route.params.questionId),
      language: selectedLanguage.value,
      code: code.value
    })
    const questionSubmitId = res.data
    // 复用现有轮询逻辑（与 ViewQuestionView 完全一致）
    startPolling(questionSubmitId)
  } catch (err: any) {
    if (err.isRateLimit) {
      // 限流处理逻辑（与 ViewQuestionView 一致）
      handleRateLimitError(err.message)
    }
  }
}
```

> 轮询逻辑与 `ViewQuestionView` 完全一致：通过 `question_submit_id` 调用 `POST /api/question_submit/list/page` 查询判题状态。

---

### 7.4 考试结果/排行榜页 `/exam/:id/result`（`ExamResultView.vue`）

#### 布局

```
┌────────────────────────────────────────────────────────────┐
│  2026年春季期中考试 — 排行榜             已结束              │
│  共 32 人参与 | 总分 500 分                                  │
├────┬──────────┬────────────┬────────────┬──────────────────┤
│ 排名│ 用户      │ 得分        │ 完成时间    │ 各题得分          │
├────┼──────────┼────────────┼────────────┼──────────────────┤
│ 🥇 1│ 张三      │ 450 / 500  │ 09:42:13   │ ✅100 ✅100 ✅150│
│ 🥈 2│ 李四      │ 400 / 500  │ 10:05:22   │ ✅100 ✅100 ❌0  │
│  3 │ 王五      │ 350 / 500  │ 10:31:08   │ ✅100 ❌0  ✅150│
│    │           │            │            │                  │
│  - │ 我的成绩   │ 200 / 500  │ 10:55:00   │ ❌0   ✅100 ✅100│
└────┴──────────┴────────────┴────────────┴──────────────────┘
                              [< 1  2  3 ... >]
```

**「我的成绩」**：若当前用户不在当前页，在表格底部固定显示一行（高亮背景 `#FFF7ED`，左侧橙色竖线）。

#### 字段来源

| 展示字段 | 来源 | 说明 |
|---------|------|------|
| 排名 | `ExamRecordVO.rank` | 后端计算，RANK() OVER |
| 用户信息 | `ExamRecordVO.userName` / `userAvatar` | |
| 得分 | `ExamRecordVO.totalScore` | 分子/分母格式 |
| 完成时间 | `ExamRecordVO.finishTime` | 格式化为 `HH:mm:ss` |
| 各题得分 | `ExamRecordVO.questionScores` | 列表，含 `isAccepted` |
| 我的成绩 | 同上，通过 `userId === loginUser.id` 识别 | 固定显示在底部 |

#### 各题得分徽章样式

```vue
<template #questionScores="{ record }">
  <a-space>
    <span v-for="qs in record.questionScores" :key="qs.questionId"
          :style="{ color: qs.isAccepted ? '#00AF9B' : '#8C8C8C',
                    fontWeight: qs.isAccepted ? 600 : 400 }">
      {{ qs.isAccepted ? '✅' : '❌' }}{{ qs.score }}
    </span>
  </a-space>
</template>
```

#### 页面逻辑说明

- 考试进行中也可查看排行榜（实时刷新，激励竞争）
- 提供「刷新」按钮（手动触发），不自动轮询
- 排行榜接口：`POST /api/exam/rank`
- 分页：每页 50 条

---

### 7.5 管理考试页 `/manage/exam`（`ManageExamView.vue`）

**风格参考**：与现有 `ManageQuestionView.vue` 完全一致。

#### 布局

```
┌──────────────────────────────────────────────────────────────┐
│  考试管理                                  [ + 创建考试 ]      │
├────────────────────┬──────────────────┬───────┬─────────────┤
│  考试名称           │  时间             │ 状态  │  操作        │
├────────────────────┼──────────────────┼───────┼─────────────┤
│  2026年春季期中     │ 04-10 09:00~11:00│ 进行中│ [编辑][取消]  │
│  2026年春季期末     │ 06-20 09:00~12:00│ 未开始│ [编辑][删除]  │
│  2026年寒假选拔赛   │ 01-15 14:00~16:00│ 已结束│ [查看结果]   │
└────────────────────┴──────────────────┴───────┴─────────────┘
                              [< 1  2  3 ... >]
```

#### 操作按钮逻辑

| 状态 | 可用操作 |
|------|---------|
| 未开始 | `编辑` → 跳转编辑页，`删除` → 确认后逻辑删除 |
| 进行中 | `编辑`（仅可改说明/结束时间），`取消考试` → 弹确认框，置 status=3 |
| 已结束 | `查看结果` → 跳转 `/exam/:id/result` |
| 已取消 | 无操作（灰显） |

#### 接口

| 操作 | 接口 |
|------|------|
| 获取列表 | `POST /api/exam/list/page`（admin 接口，含全部状态） |
| 删除考试 | `POST /api/exam/delete` |
| 取消考试 | `POST /api/exam/update` 传 `{ id, status: 3 }` |
| 跳转编辑 | 路由跳转 `/manage/exam/edit?id=xxx` |
| 创建考试 | 路由跳转 `/manage/exam/add` |

---

### 7.6 创建/编辑考试页 `/manage/exam/add` 和 `/manage/exam/edit`（`AddExamView.vue`）

**风格参考**：与 `AddQuestionView.vue` 完全一致（表单布局、Markdown 编辑器、按钮位置）。

#### 布局

```
┌──────────────────────────────────────────────────────────────┐
│  创建考试 / 编辑考试                                           │
├──────────────────────────────────────────────────────────────┤
│  考试名称 *        [ 输入考试名称 ]                            │
│  是否公开报名      [ ● 公开 ○ 仅管理员报名 ]                   │
│  开始时间 *        [ 日期时间选择器 ]                          │
│  结束时间 *        [ 日期时间选择器 ]                          │
├──────────────────────────────────────────────────────────────┤
│  考试说明（Markdown 编辑器，使用 MdEditor 组件）               │
│  ┌──────────────────────────────────────────────────────────┐│
│  │  编辑区（左）                │  预览区（右）               ││
│  └──────────────────────────────────────────────────────────┘│
├──────────────────────────────────────────────────────────────┤
│  考试题目                                 [ + 从题库添加题目 ] │
│  ┌─────┬──────────────┬──────────┬────────┬────────────────┐ │
│  │ 序号 │ 题目标题      │ 标签     │  分值  │ 操作           │ │
│  ├─────┼──────────────┼──────────┼────────┼────────────────┤ │
│  │  1  │ 两数之和      │ 哈希表   │ [ 100] │ [↑][↓][移除]   │ │
│  │  2  │ 链表反转      │ 链表     │ [ 100] │ [↑][↓][移除]   │ │
│  └─────┴──────────────┴──────────┴────────┴────────────────┘ │
├──────────────────────────────────────────────────────────────┤
│                                     [ 取消 ]   [ 保存考试 ]   │
└──────────────────────────────────────────────────────────────┘
```

#### 「从题库添加题目」弹窗

点击按钮后弹出 `a-modal`，内含题库搜索表格（复用题目列表的搜索/表格逻辑）：

```
┌───────────────────────────────────────────────────┐
│  从题库选择题目                              ✕     │
│  搜索框 [              ]  标签筛选 [...]  [搜索]   │
│  ┌────┬──────────────┬────────┬────────┐          │
│  │ 选  │ 题目标题      │ 标签   │ 通过率  │          │
│  ├────┼──────────────┼────────┼────────┤          │
│  │ □  │ 两数之和      │ 哈希表  │ 68.2% │          │
│  │ ☑  │ 链表反转      │ 链表   │ 54.1% │          │
│  └────┴──────────────┴────────┴────────┘          │
│  分值：[ 100 ] 分（选中题目统一设置，可后续单独修改）│
│                         [ 取消 ]  [ 添加选中题目 ] │
└───────────────────────────────────────────────────┘
```

#### 表单校验规则

| 字段 | 校验规则 |
|------|---------|
| 考试名称 | 必填，不超过 256 字符 |
| 开始时间 | 必填，必须晚于当前时间（新建时） |
| 结束时间 | 必填，必须晚于开始时间 |
| 题目列表 | 至少添加 1 道题目 |
| 题目分值 | 每题 1~10000 的整数 |

#### 编辑模式初始化

路由含 `?id=xxx` 时为编辑模式：
1. 调用 `getExamVO(id)` 回显考试基本信息
2. 从 `ExamVO.questions` 回显题目列表
3. 提交调用 `updateExam()` 而非 `addExam()`

---

## 八、新增公共组件

### 8.1 `ExamCountdown.vue`（考场倒计时）

```vue
<template>
  <!-- 嵌入 ExamDetailView 和 ExamRoomView 顶部 -->
  <span :style="{ color: countdownColor, fontWeight: 600, fontSize: '16px' }">
    ⏱ {{ countdownText }}
  </span>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'

/**
 * targetTime：倒计时目标时间（ISO 字符串）
 * - 考试未开始时：传 exam.startTime，倒计时到开考
 * - 考试进行中时：传 exam.endTime，倒计时到结束
 */
const props = defineProps<{ targetTime: string }>()

const now = ref(Date.now())
let timer: ReturnType<typeof setInterval>

onMounted(() => {
  timer = setInterval(() => { now.value = Date.now() }, 1000)
})
onUnmounted(() => clearInterval(timer))

const diffSec = computed(() => Math.max(0, Math.floor((new Date(props.targetTime).getTime() - now.value) / 1000)))

const countdownText = computed(() => {
  const d = diffSec.value
  if (d === 0) return '已到达'
  const h = Math.floor(d / 3600), m = Math.floor((d % 3600) / 60), s = d % 60
  return `${String(h).padStart(2,'0')}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}`
})

const countdownColor = computed(() => {
  const d = diffSec.value
  return d < 600 ? '#EF4743' : d < 1800 ? '#F0AD4E' : '#262626'
})
</script>
```

---

## 九、状态管理扩展（Pinia）

考试功能不需要新建 store，但可在现有 `user store` 之外新增一个轻量的 `exam store` 用于考场内全局共享状态：

```typescript
// src/store/exam.ts
import { defineStore } from 'pinia'
import type { ExamVO } from '@/types/exam'

export const useExamStore = defineStore('exam', {
  state: () => ({
    currentExam: null as ExamVO | null,
  }),
  actions: {
    setCurrentExam(exam: ExamVO) {
      this.currentExam = exam
    },
    clearCurrentExam() {
      this.currentExam = null
    }
  }
})
```

用途：`ExamDetailView` 加载考试信息后存入 store，`ExamRoomView` 直接读取（避免在考场页面重复请求考试基本信息）。

---

## 十、导航栏变更（`BasicLayout.vue`）

在现有菜单数组中追加：

```typescript
// 普通用户菜单项（追加在「提交记录」之后）
{ key: '/exams', label: '参加考试', requiresLogin: true },

// 管理员菜单项（追加在「管理题目」之后）
{ key: '/manage/exam', label: '管理考试', requiresAdmin: true }
```

---

## 十一、路由配置说明

> **以第 3.2 节路由配置为准**（含 `name` 字段，路由命名在导航和编程式跳转中均为必需）。
> 实现时直接将 §3.2 中的路由对象追加到 `BasicLayout` 的 `children` 数组，无需单独维护 `examRoutes` 数组。

完整路由配置见 §3.2，此处不再重复。

---

## 十二、开发优先级建议

| 优先级 | 页面/功能 | 说明 |
|--------|---------|------|
| P0 | 考试列表页 `/exams` | 功能入口 |
| P0 | 考试详情/报名页 `/exam/:id` | 核心流程 |
| P0 | 考场做题页（复用 ViewQuestionView） | 核心功能 |
| P1 | 排行榜页 `/exam/:id/result` | 参赛激励 |
| P1 | 管理考试列表页 `/manage/exam` | 管理功能 |
| P1 | 考场倒计时组件 `ExamCountdown.vue` | ExamDetailView 和 ExamRoomView 均依赖此组件，需同步实现 |
| P2 | 创建/编辑考试页 `/manage/exam/add` | 内容录入 |

---

## 十三、与现有页面的代码复用清单

| 现有资源 | 在考试模块中的复用方式 |
|---------|---------------------|
| `ViewQuestionView.vue` | `ExamRoomView.vue` 90% 复用布局和判题轮询逻辑 |
| `ManageQuestionView.vue` | `ManageExamView.vue` 参考表格结构和操作按钮样式 |
| `AddQuestionView.vue` | `AddExamView.vue` 参考表单布局和 MdEditor 使用方式 |
| `QuestionsView.vue` | `ExamListView.vue` 参考搜索栏 + 表格 + 分页结构 |
| `CodeEditor.vue` | `ExamRoomView.vue` 直接复用 |
| `MdEditor.vue` / `MdViewer.vue` | 考试说明编辑/展示直接复用 |
| `StatusTag` 颜色规范 | 考试状态标签沿用同一色值表 |
| `request.ts` 响应拦截器 | 考试接口限流（42900）处理逻辑完全复用 |
