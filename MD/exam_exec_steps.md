# XI OJ 考试功能 — 执行步骤

> 版本：V1.0 | 日期：2026-04-10
> 参考文档：`exam_backend_dev.md` / `exam_frontend_dev.md`
> 开发原则：前后端并行推进，后端 P0 阶段完成后前端才能联调

---

## 执行顺序总览

```
阶段一：建表（P0）
    ↓
阶段二：后端实体/DTO/VO/ErrorCode（P0）
    ↓                              ↓
阶段三：后端服务层（P0）      阶段五：前端类型/API（P0，并行）
    ↓                              ↓
阶段四：后端Controller/集成（P1）  阶段六：前端核心页面（P0，可对照文档先写静态）
    ↓                              ↓
    ←———— 后端 P0 完成后开始联调 ————→
    ↓
阶段七：排行榜/管理页（P1）
    ↓
阶段八：创建/编辑页（P2）
    ↓
阶段九：路由/导航（P1，可与七同步）
    ↓
阶段十：测试与验收（P2）
```

---

## 阶段一：数据库准备（P0）

**目标**：建好4张新表，为后续所有开发提供数据基础。

### 步骤 1.1 — 执行 DDL 建表

在数据库 `oj_db` 中依次执行建表语句（来自后端文档第三章）：

```
顺序：exam → exam_question → exam_record → exam_submission
（按外键依赖顺序执行）
```

在项目 `sql/` 目录下新建 `exam_tables.sql`，集中存放4张表的 DDL。

**验收标准**：`SHOW TABLES` 可见4张新表，`DESC exam_submission` 字段与文档一致。

---

## 阶段二：后端基础结构（P0）

**目标**：搭建实体、Mapper、DTO/VO 脚手架，为服务层开发提供类型支撑。

### 步骤 2.1 — 创建4个实体类

位置：`src/main/java/com/XI/xi_oj/model/entity/`

| 文件 | 对应文档 |
|------|---------|
| `Exam.java` | §4.1 |
| `ExamQuestion.java` | §4.2 |
| `ExamRecord.java` | §4.3 |
| `ExamSubmission.java` | §4.4 |

注意事项：使用 `@TableName`、`@TableId(type = IdType.AUTO)`、`@TableLogic`（`isDelete` 字段）、`@Data`，时间字段用 `LocalDateTime`。

### 步骤 2.2 — 创建4个 Mapper 接口

位置：`src/main/java/com/XI/xi_oj/mapper/`

```
ExamMapper.java           extends BaseMapper<Exam>
ExamQuestionMapper.java   extends BaseMapper<ExamQuestion>
ExamRecordMapper.java     extends BaseMapper<ExamRecord>
ExamSubmissionMapper.java extends BaseMapper<ExamSubmission>
                          + 自定义 rankPage() 方法（RANK() OVER 窗口函数）
```

`ExamSubmissionMapper` 需在 `resources/mapper/` 下创建 XML 映射文件，编写排行榜 SQL（文档 §6.3）。

### 步骤 2.3 — 创建 DTO 类

位置：`src/main/java/com/XI/xi_oj/model/dto/exam/`

| 文件 | 说明 |
|------|------|
| `ExamAddRequest.java` | 创建考试（§5.1） |
| `ExamUpdateRequest.java` | 更新考试（§5.1） |
| `ExamQueryRequest.java` | 分页查询，**含 `examId` 字段**（供排行榜接口使用） |
| `ExamQuestionAddRequest.java` | 添加题目（§5.1） |
| `ExamQuestionDeleteRequest.java` | 移除题目（§5.1） |
| `ExamQuestionUpdateRequest.java` | 更新题目分值/排序，含 `examId`、`questionId`、`score`、`sortOrder` |
| `ExamJoinRequest.java` | 报名（§5.1） |
| `ExamCodeSubmitRequest.java` | 考场提交代码（§5.1） |

### 步骤 2.4 — 创建 VO 类

位置：`src/main/java/com/XI/xi_oj/model/vo/`

| 文件 | 说明 |
|------|------|
| `ExamVO.java` | 考试视图（§5.2） |
| `ExamQuestionVO.java` | 考试内题目视图（§5.2） |
| `ExamRecordVO.java` | 参赛记录/排行榜视图（§5.2） |
| `QuestionScoreVO.java` | 排行榜每题得分（§5.2） |

### 步骤 2.5 — 扩展 ErrorCode

文件：`src/main/java/com/XI/xi_oj/common/ErrorCode.java`

在已有 `OPERATION_ERROR` 之后追加：

```java
EXAM_NOT_FOUND(44001, "考试不存在"),
EXAM_NOT_STARTED(44002, "考试尚未开始"),
EXAM_ENDED(44003, "考试已结束"),
EXAM_CANCELLED(44004, "考试已取消"),
EXAM_NOT_PUBLIC(44005, "该考试暂不接受报名"),
EXAM_ALREADY_JOINED(44006, "您已报名该考试"),
EXAM_NOT_JOINED(44007, "您尚未报名该考试"),
EXAM_QUESTION_NOT_FOUND(44008, "该题目不在本次考试范围内"),
EXAM_QUESTION_ALREADY_EXISTS(44009, "该题目已在考试中");
```

---

## 阶段三：后端服务层（P0/P1）

### 步骤 3.1 — 实现 `ExamService`（P0）

位置：`src/main/java/com/XI/xi_oj/service/`

接口定义（完整，含题目管理方法）：

```java
public interface ExamService extends IService<Exam> {
    long addExam(ExamAddRequest request, User loginUser);
    void updateExam(ExamUpdateRequest request, User loginUser);
    void deleteExam(long examId, User loginUser);
    Page<ExamVO> listExamVOByPage(ExamQueryRequest request, User loginUser);
    Page<ExamVO> listExamByPageForAdmin(ExamQueryRequest request);
    ExamVO getExamVO(long examId, User loginUser);
    ExamVO toVO(Exam exam, User loginUser);
    void addExamQuestion(ExamQuestionAddRequest request);
    void deleteExamQuestion(ExamQuestionDeleteRequest request);
    void updateExamQuestion(ExamQuestionUpdateRequest request);
}
```

`ExamServiceImpl` 实现要点：

- `addExam()`：校验 `endTime > startTime`，初始 `status = 0`，写库返回 ID
- `updateExam()`：仅允许 status=0/1 的考试被修改；`status` 字段仅允许传 3（取消）
- `getExamVO()`：status=0 时不填充 `questions`（防题目泄露）
- `toVO()`：填充 `questionCount`（COUNT exam_question）、`participantCount`（COUNT exam_record）、`joined`（查当前用户是否在 exam_record）

### 步骤 3.2 — 实现 `ExamStatusScheduler`（P0）

位置：`src/main/java/com/XI/xi_oj/job/ExamStatusScheduler.java`

```java
@Scheduled(fixedRate = 30_000)
public void syncExamStatus() {
    LocalDateTime now = LocalDateTime.now();
    // 未开始 → 进行中
    examService.lambdaUpdate()
        .eq(Exam::getStatus, 0).le(Exam::getStartTime, now)
        .eq(Exam::getIsDelete, 0).set(Exam::getStatus, 1).update();
    // 进行中 → 已结束
    examService.lambdaUpdate()
        .eq(Exam::getStatus, 1).lt(Exam::getEndTime, now)
        .eq(Exam::getIsDelete, 0).set(Exam::getStatus, 2).update();
}
```

在 `MainApplication.java` 添加 `@EnableScheduling`（若未开启）。

### 步骤 3.3 — 实现 `ExamRecordService`（P0）

位置：`src/main/java/com/XI/xi_oj/service/`

接口方法：

```java
long joinExam(ExamJoinRequest request, User loginUser);
long submitInExam(ExamCodeSubmitRequest request, User loginUser);
ExamRecord getMyRecord(long examId, User loginUser);
```

`joinExam()` 实现要点：
1. 查询考试，校验 `status` 为 0 或 1 且 `isPublic = 1`
2. 检查是否已报名（`UNIQUE KEY uk_exam_user` 兜底，捕获重复键异常转为业务错误）
3. 插入 `exam_record`（`status = 0`）

`submitInExam()` 实现要点：
1. 查询考试，校验 `status = 1`（进行中）
2. 兜底时间校验：`LocalDateTime.now().isBefore(exam.getEndTime())`，过期则抛 `EXAM_ENDED`
3. 校验用户已报名；校验 `questionId` 属于该考试
4. 调用 `QuestionSubmitService.doQuestionSubmit()` 获得 `questionSubmitId`
5. Upsert `exam_submission`（`exam_id + user_id + question_id` 为 UNIQUE KEY）
6. 更新 `exam_record`：`status = 1`（考试中，幂等），`finish_time = NOW()`

### 步骤 3.4 — 实现 `ExamSubmissionService`（P1）

位置：`src/main/java/com/XI/xi_oj/service/`

接口方法：

```java
void onJudgeComplete(long questionSubmitId, QuestionSubmit questionSubmit);
List<QuestionScoreVO> getMyScores(long examId, long userId);
Page<ExamRecordVO> getRankPage(long examId, long current, long pageSize);
```

`onJudgeComplete()` 实现要点（`@Transactional`）：
1. 查 `exam_submission` WHERE `question_submit_id = questionSubmitId`
2. 若不存在或 `is_accepted = 1`，直接返回
3. 解析 `questionSubmit.getJudgeInfo()` 判断是否 Accepted
4. 若通过：查 `exam_question.score` 获得满分，更新 `exam_submission.score + is_accepted = 1`
5. 重新 SUM 汇总 `exam_record.total_score`

`getRankPage()` 实现要点：
- 调用 `ExamSubmissionMapper` 自定义 SQL（含 `RANK() OVER`），组装 `ExamRecordVO`（含 `questionScores`）

---

## 阶段四：后端 Controller 与集成（P1）

### 步骤 4.1 — 编写 `ExamController`

位置：`src/main/java/com/XI/xi_oj/controller/ExamController.java`

基于文档 §8 骨架实现，关键注意：
- `getRank()` 接收 `ExamQueryRequest`，通过 `request.getExamId()` 取考试 ID
- 题目管理方法委托给 `examService.addExamQuestion / deleteExamQuestion / updateExamQuestion`
- 接口路径前缀 `/exam`（全局已配置 `/api` 前缀）

### 步骤 4.2 — 扩展 `JudgeServiceImpl`

文件：`src/main/java/com/XI/xi_oj/judge/JudgeServiceImpl.java`

在 `doJudge()` 方法末尾（更新 QuestionSubmit 状态之后）追加：

```java
// 第七步：若属于考场提交，更新考场得分（异常不影响判题主流程）
try {
    examSubmissionService.onJudgeComplete(questionSubmitId, questionSubmit);
} catch (Exception e) {
    log.error("考场得分更新失败，questionSubmitId={}", questionSubmitId, e);
}
```

注意：通过 `@Lazy` 注入 `ExamSubmissionService`，避免与 `QuestionSubmitService` 产生循环依赖。

### 步骤 4.3 — Knife4j 联调验证

启动项目，访问 `/api/doc.html`，验证以下接口均正常响应：

| 接口 | 预期 |
|------|------|
| `POST /api/exam/add` | 返回新考试 ID |
| `POST /api/exam/question/add` | 返回 true |
| `POST /api/exam/join` | 返回 examRecordId |
| `POST /api/exam/submit` | 返回 questionSubmitId |
| `POST /api/exam/rank` | 返回分页排行榜 |

---

## 阶段五：前端类型与 API 层（P0，可与后端并行）

### 步骤 5.1 — 创建类型定义文件

文件：`frontend/OJ_frontend/src/types/exam.ts`

基于前端文档 §5，`ExamQueryRequest` 需含 `examId?: number` 字段。

### 步骤 5.2 — 创建接口封装文件

文件：`frontend/OJ_frontend/src/api/exam.ts`

基于前端文档 §6，所有接口路径与后端 `/exam/*` 对应。

---

## 阶段六：前端组件与核心页面（P0）

### 步骤 6.1 — 创建 `ExamCountdown.vue` 组件

文件：`frontend/OJ_frontend/src/components/ExamCountdown.vue`

prop 使用 `targetTime`（倒计时目标时间）：

```typescript
const props = defineProps<{ targetTime: string }>()
const diffSec = computed(() =>
  Math.max(0, Math.floor((new Date(props.targetTime).getTime() - now.value) / 1000))
)
```

使用规则：

| 场景 | 传值 |
|------|------|
| 考试未开始，倒计时到开考 | `:target-time="exam.startTime"` |
| 考试进行中，倒计时到结束 | `:target-time="exam.endTime"` |

颜色规则：`≥1800s` → `#262626`，`600~1800s` → `#F0AD4E`，`<600s` → `#EF4743`

### 步骤 6.2 — 创建 `ExamListView.vue`（考试列表页）

文件：`frontend/OJ_frontend/src/views/exam/ExamListView.vue`

参考前端文档 §7.1，风格对齐 `QuestionsView.vue`：
- 搜索栏：名称输入框 + 状态下拉筛选
- 表格列：考试名称、时间区间、状态标签、题目数、操作按钮
- 操作按钮随 `status + joined` 动态变化（详见前端文档 §7.1 操作按钮逻辑表）
- 进行中时，时间列下方嵌入 `<ExamCountdown :target-time="record.endTime" />`
- 分页：每页10条

### 步骤 6.3 — 创建 `ExamDetailView.vue`（考试详情/报名页）

文件：`frontend/OJ_frontend/src/views/exam/ExamDetailView.vue`

参考前端文档 §7.2，分两种布局：

**status=0（未开始）**：
- 基本信息 + `<ExamCountdown :target-time="exam.startTime" />`
- Markdown 渲染考试说明
- 「立即报名」/「已报名」按钮

**status=1（进行中，已报名）**：
- `<ExamCountdown :target-time="exam.endTime" />` 置于顶部右侧
- 题目列表表格（从 `ExamVO.questions` 渲染，含各题得分状态）
- 题目状态：`myAccepted=true` → 已通过；`myScore=0` 且有提交 → 判题中；无提交 → 未提交
- **判题中处理**：每5s 调用 `getExamVO` 整体刷新（无需单独轮询）
- 当前总分 = 前端 SUM `myScore`，点击题目行跳转 `ExamRoomView`

### 步骤 6.4 — 创建 `ExamRoomView.vue`（考场做题页）

文件：`frontend/OJ_frontend/src/views/exam/ExamRoomView.vue`

参考前端文档 §7.3，高度复用 `ViewQuestionView.vue`：
- 顶部固定：考试名 + 题目序号（X/N）+ `<ExamCountdown :target-time="exam.endTime" />`
- 左侧：题目信息面板 + 本题得分信息 + 上一题/下一题导航
- 右侧：`CodeEditor.vue` + 提交按钮 + 判题结果区
- 提交接口：`submitInExam()`（传 `examId + questionId + language + code`）
- 判题轮询：复用 `ViewQuestionView` 的精确轮询逻辑（通过 `questionSubmitId` 查询）
- 返回考场按钮：跳转 `/exam/:id`

---

## 阶段七：前端排行榜与管理页（P1）

### 步骤 7.1 — 创建 `ExamResultView.vue`（排行榜页）

文件：`frontend/OJ_frontend/src/views/exam/ExamResultView.vue`

参考前端文档 §7.4：
- 接口：`POST /api/exam/rank`（body 传 `examId`）
- 表格列：排名（前3名用奖牌）、用户、得分/总分、完成时间、各题得分徽章（`✅/❌`）
- 当前用户行：不在当前页时固定显示在底部，橙色左边框 + `#FFF7ED` 背景高亮
- 提供手动「刷新」按钮，不自动轮询
- 分页：每页50条

### 步骤 7.2 — 创建 `ManageExamView.vue`（管理考试页）

文件：`frontend/OJ_frontend/src/views/exam/ManageExamView.vue`

参考前端文档 §7.5，风格对齐 `ManageQuestionView.vue`：

| 考试状态 | 可用操作 |
|---------|---------|
| 未开始 | 「编辑」→ `/manage/exam/edit?id=xxx`，「删除」→ 确认后逻辑删除 |
| 进行中 | 「编辑」（限定字段），「取消考试」→ 确认框，传 `status=3` |
| 已结束 | 「查看结果」→ `/exam/:id/result` |
| 已取消 | 灰显，无操作 |

---

## 阶段八：前端管理员创建/编辑页（P2）

### 步骤 8.1 — 创建 `AddExamView.vue`（创建/编辑考试页）

文件：`frontend/OJ_frontend/src/views/exam/AddExamView.vue`

参考前端文档 §7.6，风格对齐 `AddQuestionView.vue`：
- 表单：名称（必填）、是否公开、开始/结束时间（日期时间选择器）
- 考试说明：复用 `MdEditor.vue`
- 题目列表：可排序（拖拽/↑↓）、可单独修改分值、可移除
- 「从题库添加题目」弹窗：复用题目搜索表格，支持多选，统一设置分值
- 校验规则：开始时间>当前（新建时），结束>开始，至少1道题，分值1~10000
- 编辑模式（路由含 `?id=xxx`）：先调 `getExamVO(id)` 回显，提交调 `updateExam`

### 步骤 8.2 — 创建 `exam store`（Pinia）

文件：`frontend/OJ_frontend/src/store/exam.ts`

参考前端文档 §9，在考场内共享当前考试信息：
- `ExamDetailView` 加载后调 `setCurrentExam(exam)` 存入 store
- `ExamRoomView` 直接从 store 读取，避免重复请求

---

## 阶段九：路由与导航配置（P1）

### 步骤 9.1 — 更新路由配置

文件：`frontend/OJ_frontend/src/router/routes.ts`

追加到 `BasicLayout` 的 `children` 数组（含 `name` 字段，以前端文档 §3.2 为准）：

```typescript
{ path: '/exams', name: 'ExamList',
  component: () => import('@/views/exam/ExamListView.vue'),
  meta: { requiresRole: 'user', title: '参加考试', showInMenu: true } },
{ path: '/exam/:id', name: 'ExamDetail',
  component: () => import('@/views/exam/ExamDetailView.vue'),
  meta: { requiresRole: 'user' } },
{ path: '/exam/:examId/problem/:questionId', name: 'ExamRoom',
  component: () => import('@/views/exam/ExamRoomView.vue'),
  meta: { requiresRole: 'user' } },
{ path: '/exam/:id/result', name: 'ExamResult',
  component: () => import('@/views/exam/ExamResultView.vue'),
  meta: { requiresRole: 'user' } },
{ path: '/manage/exam', name: 'ManageExam',
  component: () => import('@/views/exam/ManageExamView.vue'),
  meta: { requiresRole: 'admin', title: '管理考试', showInMenu: true } },
{ path: '/manage/exam/add', name: 'AddExam',
  component: () => import('@/views/exam/AddExamView.vue'),
  meta: { requiresRole: 'admin' } },
{ path: '/manage/exam/edit', name: 'EditExam',
  component: () => import('@/views/exam/AddExamView.vue'),
  meta: { requiresRole: 'admin' } }
```

### 步骤 9.2 — 更新导航栏

文件：`frontend/OJ_frontend/src/layouts/BasicLayout.vue`

```
普通用户菜单（「提交记录」之后插入）：
{ key: '/exams', label: '参加考试', requiresLogin: true }

管理员菜单（「管理题目」之后追加）：
{ key: '/manage/exam', label: '管理考试', requiresAdmin: true }
```

最终导航结构：
```
[ Logo ]  [ 浏览题目 ]  [ 提交记录 ]  [ 参加考试 ]  [ 创建题目 ]  [ 头像 ▾ ]
                                                         ↓（管理员下拉）
                                              [ 管理题目 ] [ 管理用户 ] [ 管理考试 ]
```

---

## 阶段十：测试与验收（P2）

### 步骤 10.1 — 后端测试

参考文件：`src/test/java/com/XI/xi_oj/judge/codesandbox/CodeSandboxTest.java`

| 测试场景 | 验证点 |
|---------|-------|
| 创建考试 + 添加题目 | `exam` 和 `exam_question` 表正确写入 |
| 定时任务触发 | 模拟时间推进，`exam.status` 自动变更 0→1→2 |
| 用户报名 | `exam_record` 写入；重复报名返回 `EXAM_ALREADY_JOINED` |
| 考场提交 → 判题完成 | `exam_submission.score` 和 `exam_record.total_score` 正确更新 |
| 排行榜查询 | RANK() OVER 返回正确顺序（总分降序，同分按 finish_time 升序） |
| 考试已结束时提交 | 返回 `EXAM_ENDED` 错误码 |

### 步骤 10.2 — 前后端联调

1. **管理员流程**：创建考试 → 添加题目 → 等待定时任务变为进行中（或手动触发）
2. **用户流程**：查看考试列表 → 报名 → 进入考场 → 提交代码 → 查看判题结果和得分
3. **排行榜**：考试结束后访问结果页，验证排名计算正确
4. **边界验证**：考试时间到期后提交被拒绝；重复报名被拒绝

---

## 开发优先级汇总

| 优先级 | 任务 | 所属阶段 |
|--------|------|---------|
| P0 | 建4张表 | 阶段一 |
| P0 | 4个实体类 + Mapper（含排行榜自定义SQL） | 阶段二 |
| P0 | DTO（含 ExamQueryRequest.examId、ExamQuestionUpdateRequest）+ VO + ErrorCode | 阶段二 |
| P0 | ExamService（含题目管理方法） | 阶段三 |
| P0 | ExamStatusScheduler + @EnableScheduling | 阶段三 |
| P0 | ExamRecordService（报名 + 提交 + exam_record.status 更新） | 阶段三 |
| P0 | `src/types/exam.ts`（含 examId）+ `src/api/exam.ts` | 阶段五 |
| P0 | `ExamCountdown.vue`（targetTime prop） | 阶段六 |
| P0 | `ExamListView.vue` | 阶段六 |
| P0 | `ExamDetailView.vue` | 阶段六 |
| P0 | `ExamRoomView.vue` | 阶段六 |
| P1 | ExamSubmissionService（onJudgeComplete + 排行榜） | 阶段三 |
| P1 | ExamController（完整实现） | 阶段四 |
| P1 | 扩展 JudgeServiceImpl（@Lazy 注入，追加回调） | 阶段四 |
| P1 | Knife4j 联调验证 | 阶段四 |
| P1 | `ExamResultView.vue` | 阶段七 |
| P1 | `ManageExamView.vue` | 阶段七 |
| P1 | 路由配置 + 导航栏更新 | 阶段九 |
| P2 | `AddExamView.vue` | 阶段八 |
| P2 | `exam store`（Pinia） | 阶段八 |
| P2 | 后端测试 + 前后端联调 | 阶段十 |

---

*文档版本：V1.0 | 生成日期：2026-04-10*
