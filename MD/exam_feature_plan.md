# XI OJ 考试功能拓展 — 问题审查与执行计划

> 生成日期：2026-04-10
> 依赖文档：`exam_backend_dev.md` / `exam_frontend_dev.md`
> 基准分支：main

---

## 一、文档审查：发现的问题

### 1.1 后端文档问题

#### 🔴 严重问题

**问题1：`ExamController.getRank()` 调用了不存在的 `getExamId()` 方法**

文档第八章 `ExamController` 骨架中：
```java
@PostMapping("/rank")
public BaseResponse<Page<ExamRecordVO>> getRank(@RequestBody ExamQueryRequest request) {
    return ResultUtils.success(
        examSubmissionService.getRankPage(request.getExamId(),   // ❌ ExamQueryRequest 无此字段
                                         request.getCurrent(), request.getPageSize()));
}
```
`ExamQueryRequest`（第五章）只有 `title`、`status`、`current`、`pageSize` 四个字段，根本没有 `examId`。

**修复方案**：在 `ExamQueryRequest` 中新增 `examId` 字段，或为排行榜单独创建 `ExamRankQueryRequest`（推荐，职责更清晰）。

---

**问题2：`ExamService` 接口未定义 `addExamQuestion` / `deleteExamQuestion` 方法**

Controller（第八章）调用了：
```java
examService.addExamQuestion(request);    // ❌ ExamService 接口第7.2节没有定义该方法
examService.deleteExamQuestion(request); // ❌ 同上
```
第七章 `ExamService` 接口只定义了6个方法，没有题目管理相关方法。

**修复方案**：在 `ExamService` 接口（或 `ExamQuestionService` 接口）中补充以下方法：
```java
void addExamQuestion(ExamQuestionAddRequest request);
void deleteExamQuestion(ExamQuestionDeleteRequest request);
void updateExamQuestion(ExamQuestionUpdateRequest request); // 对应 /question/update 接口
```
同时补充 `ExamQuestionUpdateRequest` DTO（接口表中有 `/exam/question/update`，但 DTO 章节未定义）。

---

#### 🟡 中等问题

**问题3：`exam_record.status` 状态更新逻辑缺失**

表设计（第3.4节）定义 `exam_record.status` 为 `0=已报名 1=考试中 2=已结束`，但：
- `ExamStatusScheduler` 定时任务只更新 `exam.status`，从未同步更新 `exam_record.status`；
- `submitInExam` 逻辑（第6.3节）也没有将 `exam_record.status` 从 0 改为 1。

**修复方案**：在 `submitInExam` 首次写入 `exam_submission` 时，同步将 `exam_record.status` 更新为 1；考试结束后（可由 `ExamStatusScheduler` 顺带）更新对应 `exam_record.status` 为 2，或在排行榜生成逻辑中处理。

---

**问题4：`ExamStatusScheduler` 中 `startedCount` 变量错误且无意义**

```java
// lambdaUpdate().update() 返回 boolean，而非影响行数
int startedCount = examService.lambdaUpdate()...update() ? 1 : 0;
// 且 startedCount 变量从未被使用
```
`MyBatis-Plus` 的 `update()` 返回 `boolean`（是否执行成功），不是影响行数，赋给 `startedCount` 后也从未使用，是无效代码。

**修复方案**：直接调用 `update()` 不赋值，或改用 `getBaseMapper().update(...)` 获取实际影响行数。

---

**问题5：`onJudgeComplete` 方法参数命名有误导性**

```java
void onJudgeComplete(long questionSubmitId, QuestionSubmit judgeResult);
```
参数名 `judgeResult` 暗示是判题结果，但实际类型是 `QuestionSubmit`（整个提交记录对象，含判题信息）。

**修复方案**：改名为 `questionSubmit`，与现有代码风格一致：
```java
void onJudgeComplete(long questionSubmitId, QuestionSubmit questionSubmit);
```

---

**问题6：接口文档权限标注与实现逻辑不一致**

第6.1节接口表中 `POST /api/exam/list/page/vo` 标注「需登录」，但 Controller 代码使用：
```java
User loginUser = userService.getLoginUserPermitNull(httpRequest); // 允许未登录
```
这说明考试列表允许未登录访问（合理），但文档权限列写「需登录」，存在文档与实现的矛盾。

**修复方案**：将接口表中该接口权限改为「无需登录（未登录时 `joined` 字段为 null）」。

---

### 1.2 前端文档问题

#### 🔴 严重问题

**问题7：`ExamCountdown.vue` prop 语义在考试未开始场景下被混用**

组件只定义了 `endTime` 这一个 prop，但在第7.2节（考试详情页，考试未开始场景）说明：
> "ExamCountdown 组件接收 `endTime` prop，此处传入 `exam.startTime`"

把 `startTime` 传给名为 `endTime` 的 prop，语义混乱，维护时极易出错。

**修复方案**：将 prop 重命名为 `targetTime`，或增加一个 `mode` prop（`'start' | 'end'`），明确倒计时目标。

推荐修改为：
```vue
<!-- 组件 props -->
const props = defineProps<{ targetTime: string }>()

<!-- 考试未开始：倒计时到开始时间 -->
<ExamCountdown :target-time="exam.startTime" />

<!-- 考试进行中：倒计时到结束时间 -->
<ExamCountdown :target-time="exam.endTime" />
```

---

#### 🟡 中等问题

**问题8：`ExamQueryRequest` 类型定义缺少 `examId` 字段（与后端问题1联动）**

`src/types/exam.ts` 中 `ExamQueryRequest` 只有 `title`、`status`、`current`、`pageSize`，与后端调用 `request.getExamId()` 的地方对应不上。

**修复方案**：与后端同步，在 `ExamQueryRequest` 中加 `examId?: number`，或前端排行榜接口直接传独立参数（已在 `src/api/exam.ts` 的 `getExamRank` 函数中这样做了，但类型定义应保持一致）。

---

#### 🟢 轻微问题

**问题9：路由配置在两处重复（第3.2节 vs 第11节）**

路由配置出现在 3.2 节和第 11 节两处，内容略有差异：
- 第3.2节用了对象字面量形式（含 `name` 字段）
- 第11节用了 `examRoutes` 数组形式（有一个路由直接 import 而非懒加载）

实现时应以第3.2节为准（有 `name` 字段，路由命名是必要的），第11节作为补充示意。

---

**问题10：`ExamDetailView.vue` 中"判题中"状态处理方案未明确**

第7.2节说明：
> "「判题中」状态需前端轮询（复用现有 `ViewQuestionView` 的轮询逻辑），或每 5s 刷新一次 `getExamVO`"

两种方案并列，实现时会造成歧义。

**推荐方案**：在 `ExamDetailView`（题目列表页）每 5s 刷新一次 `getExamVO`（简单），在 `ExamRoomView`（做题页）复用精确轮询逻辑（用户正在做题，需要精确结果）。两个页面策略不同，分别说明更清晰。

---

### 1.3 问题汇总

| 编号 | 严重度 | 位置 | 问题描述 | 影响 |
|------|--------|------|---------|------|
| P1 | 🔴 严重 | 后端 Controller | `ExamQueryRequest` 无 `examId`，`getRank()` 编译报错 | 排行榜接口无法编译 |
| P2 | 🔴 严重 | 后端 Service | `ExamService` 接口缺 `addExamQuestion/deleteExamQuestion` 方法 | 题目管理接口编译报错 |
| P3 | 🟡 中等 | 后端 Service | `exam_record.status` 更新逻辑缺失 | 状态字段始终为0，功能残缺 |
| P4 | 🟡 中等 | 后端 Scheduler | `startedCount` 赋值错误且无效 | 代码质量问题，不影响功能 |
| P5 | 🟡 中等 | 后端 Service | `onJudgeComplete` 参数命名误导 | 可读性问题 |
| P6 | 🟢 轻微 | 后端 文档 | 接口权限标注与实现不一致 | 文档误导 |
| P7 | 🔴 严重 | 前端 组件 | `ExamCountdown` prop `endTime` 语义混用 | 开发时极易误用 |
| P8 | 🟡 中等 | 前端 类型 | `ExamQueryRequest` 缺 `examId` | 类型检查报错 |
| P9 | 🟢 轻微 | 前端 文档 | 路由配置两处重复且略有差异 | 选哪个版本造成困惑 |
| P10 | 🟢 轻微 | 前端 文档 | 判题中状态轮询方案未明确 | 实现时选择模糊 |

---

## 二、执行计划

> 开发原则：前后端并行推进，后端 P0 阶段完成后前端才能联调。
> 括号内为对应文档章节编号，方便查阅原始设计。

---

### 阶段一：数据库准备（P0）

**目标**：建好4张新表，为后续所有开发提供数据基础。

#### 步骤 1.1 — 执行 DDL 建表

在数据库 `oj_db` 中依次执行以下建表语句（来自后端文档第三章）：

```
顺序：exam → exam_question → exam_record → exam_submission
（按外键依赖顺序执行）
```

涉及文件：在项目 `sql/` 目录下新建 `exam_tables.sql`，集中存放4张表的DDL。

**验收标准**：`SHOW TABLES` 可见4张新表，`DESC exam_submission` 字段与文档一致。

---

### 阶段二：后端基础结构（P0）

**目标**：搭建实体、Mapper、DTO/VO 脚手架，为服务层开发提供类型支撑。

#### 步骤 2.1 — 创建4个实体类

位置：`src/main/java/com/XI/xi_oj/model/entity/`

| 文件 | 说明 |
|------|------|
| `Exam.java` | 考试主表实体（文档 §4.1） |
| `ExamQuestion.java` | 考试题目关联实体（§4.2） |
| `ExamRecord.java` | 用户参赛记录实体（§4.3） |
| `ExamSubmission.java` | 考场最优提交实体（§4.4） |

注意：使用 `@TableName`、`@TableId(type = IdType.AUTO)`、`@TableLogic`（`isDelete` 字段）、`@Data`。时间字段用 `LocalDateTime`。

#### 步骤 2.2 — 创建4个 Mapper 接口

位置：`src/main/java/com/XI/xi_oj/mapper/`

```java
ExamMapper.java          // extends BaseMapper<Exam>
ExamQuestionMapper.java  // extends BaseMapper<ExamQuestion>
ExamRecordMapper.java    // extends BaseMapper<ExamRecord>
ExamSubmissionMapper.java // extends BaseMapper<ExamSubmission>，
                          // 需自定义 rankPage() 方法（RANK() OVER 窗口函数）
```

`ExamSubmissionMapper` 需要在 `resources/mapper/` 下创建对应的 XML 映射文件，编写排行榜 SQL（文档 §6.3 rank SQL）。

#### 步骤 2.3 — 创建 DTO 类

位置：`src/main/java/com/XI/xi_oj/model/dto/exam/`

| 文件 | 说明 |
|------|------|
| `ExamAddRequest.java` | 创建考试（§5.1） |
| `ExamUpdateRequest.java` | 更新考试（§5.1） |
| `ExamQueryRequest.java` | 分页查询 ⚠️ **需新增 `examId` 字段**（修复问题P1/P8） |
| `ExamQuestionAddRequest.java` | 添加题目（§5.1） |
| `ExamQuestionDeleteRequest.java` | 移除题目（§5.1） |
| `ExamQuestionUpdateRequest.java` | ⚠️ **文档缺失，需新增**（修复问题P2），包含 `examId`、`questionId`、`score`、`sortOrder` |
| `ExamJoinRequest.java` | 报名（§5.1） |
| `ExamCodeSubmitRequest.java` | 考场提交代码（§5.1） |

#### 步骤 2.4 — 创建 VO 类

位置：`src/main/java/com/XI/xi_oj/model/vo/`

| 文件 | 说明 |
|------|------|
| `ExamVO.java` | 考试视图（§5.2） |
| `ExamQuestionVO.java` | 考试内题目视图（§5.2） |
| `ExamRecordVO.java` | 参赛记录视图/排行榜（§5.2） |
| `QuestionScoreVO.java` | 排行榜每题得分（§5.2） |

#### 步骤 2.5 — 扩展 ErrorCode

文件：`src/main/java/com/XI/xi_oj/common/ErrorCode.java`

新增9个枚举值（文档 §11），在已有 `OPERATION_ERROR` 之后追加：
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

### 阶段三：后端服务层（P0）

#### 步骤 3.1 — 实现 `ExamService`

位置：`src/main/java/com/XI/xi_oj/service/`

接口 `ExamService.java`（基于 §7.2，**补充缺失方法**修复问题P2）：
```java
public interface ExamService extends IService<Exam> {
    long addExam(ExamAddRequest request, User loginUser);
    void updateExam(ExamUpdateRequest request, User loginUser);
    void deleteExam(long examId, User loginUser);
    Page<ExamVO> listExamVOByPage(ExamQueryRequest request, User loginUser);
    Page<ExamVO> listExamByPageForAdmin(ExamQueryRequest request);
    ExamVO getExamVO(long examId, User loginUser);
    ExamVO toVO(Exam exam, User loginUser);
    // ⚠️ 补充的方法（修复P2）
    void addExamQuestion(ExamQuestionAddRequest request);
    void deleteExamQuestion(ExamQuestionDeleteRequest request);
    void updateExamQuestion(ExamQuestionUpdateRequest request);
}
```

`ExamServiceImpl.java` 实现要点：
- `addExam()`：校验 `endTime > startTime`，初始 `status = 0`，写库返回 ID
- `updateExam()`：仅允许 status=0/1 的考试被修改，status 字段仅允许传 3（取消）
- `getExamVO()`：status=0 时不填充 `questions` 字段（防题目泄露）
- `toVO()`：填充 `questionCount`（COUNT exam_question）、`participantCount`（COUNT exam_record）、`joined`（查 exam_record 是否有当前用户）

#### 步骤 3.2 — 实现 `ExamStatusScheduler`（定时任务）

位置：`src/main/java/com/XI/xi_oj/job/ExamStatusScheduler.java`

实现要点（参考 §9.2，**修复问题P4**）：
```java
@Scheduled(fixedRate = 30_000)
public void syncExamStatus() {
    LocalDateTime now = LocalDateTime.now();
    // 未开始 → 进行中
    examService.lambdaUpdate()
        .eq(Exam::getStatus, 0)
        .le(Exam::getStartTime, now)
        .eq(Exam::getIsDelete, 0)
        .set(Exam::getStatus, 1)
        .update(); // ✅ 不再赋值给无用变量（修复P4）

    // 进行中 → 已结束
    examService.lambdaUpdate()
        .eq(Exam::getStatus, 1)
        .lt(Exam::getEndTime, now)
        .eq(Exam::getIsDelete, 0)
        .set(Exam::getStatus, 2)
        .update();

    log.debug("[ExamStatusScheduler] 执行完毕，时间={}", now);
}
```

在主启动类 `MainApplication.java` 加 `@EnableScheduling`（如未添加）。

#### 步骤 3.3 — 实现 `ExamRecordService`

位置：`src/main/java/com/XI/xi_oj/service/`

接口方法（§7.3）：
```java
long joinExam(ExamJoinRequest request, User loginUser);
long submitInExam(ExamCodeSubmitRequest request, User loginUser);
ExamRecord getMyRecord(long examId, User loginUser);
```

`ExamRecordServiceImpl.java` 实现要点：

**`joinExam()`**：
1. 查询考试，校验 `status` 为 0 或 1 且 `isPublic = 1`
2. 检查是否已报名（`UNIQUE KEY uk_exam_user` 兜底）
3. 插入 `exam_record`（`status = 0`）

**`submitInExam()`**：
1. 查询考试，校验 `status = 1`（进行中）
2. ⚠️ 兜底校验（修复P3的一部分）：`LocalDateTime.now().isBefore(exam.getEndTime())`
3. 校验用户已报名，校验 `questionId` 属于该考试
4. 调用 `QuestionSubmitService.doQuestionSubmit()` 获得 `questionSubmitId`
5. Upsert `exam_submission`（`exam_id + user_id + question_id` UNIQUE KEY）
6. **更新 `exam_record.status = 1`（修复P3）**，更新 `finish_time = NOW()`

#### 步骤 3.4 — 实现 `ExamSubmissionService`

位置：`src/main/java/com/XI/xi_oj/service/`

接口方法（§7.4，**修复参数命名问题P5**）：
```java
// 参数名改为 questionSubmit（修复P5）
void onJudgeComplete(long questionSubmitId, QuestionSubmit questionSubmit);
List<QuestionScoreVO> getMyScores(long examId, long userId);
Page<ExamRecordVO> getRankPage(long examId, long current, long pageSize);
```

`ExamSubmissionServiceImpl.java` 实现要点：

**`onJudgeComplete()`**（事务方法，§10）：
1. 查 `exam_submission` WHERE `question_submit_id = questionSubmitId`
2. 若不存在或已通过，直接返回
3. 解析 `questionSubmit.getJudgeInfo()` 判断是否 Accepted
4. 若通过：查 `exam_question.score` 获得满分，更新 `exam_submission.score + is_accepted = 1`
5. 重新 SUM 汇总 `exam_record.total_score`

**`getRankPage()`**：
- 调用 `ExamSubmissionMapper` 中的自定义 SQL（含 `RANK() OVER`），组装 `ExamRecordVO`（含 `questionScores`）

---

### 阶段四：后端 Controller 与集成（P1）

#### 步骤 4.1 — 编写 `ExamController`

位置：`src/main/java/com/XI/xi_oj/controller/ExamController.java`

基于文档 §8 骨架，注意修复问题：
- `getRank()` 方法：`request.getExamId()` 现在可用（步骤2.3已在 `ExamQueryRequest` 加了该字段）
- `addExamQuestion()` / `deleteExamQuestion()` 调用 `examService` 中新增的方法（步骤3.1已补充）

所有接口路径前缀为 `/exam`（Spring Boot 统一配置了 `/api` 前缀）。

#### 步骤 4.2 — 扩展 `JudgeServiceImpl`

文件：`src/main/java/com/XI/xi_oj/judge/JudgeServiceImpl.java`

在 `doJudge()` 方法末尾（更新 QuestionSubmit 状态之后）追加：
```java
// 第七步：若属于考场提交，更新考场得分（异常不影响主流程）
try {
    examSubmissionService.onJudgeComplete(questionSubmitId, questionSubmit);
} catch (Exception e) {
    log.error("考场得分更新失败，questionSubmitId={}", questionSubmitId, e);
}
```

注意通过 `@Lazy` 或 `@Resource` 注入 `ExamSubmissionService`，避免循环依赖。

#### 步骤 4.3 — 通过 Knife4j 联调验证

启动项目，访问 `/api/doc.html`，验证以下接口正常：
1. `POST /api/exam/add` → 创建考试
2. `POST /api/exam/question/add` → 添加题目
3. `POST /api/exam/join` → 报名
4. `POST /api/exam/submit` → 考场提交
5. `POST /api/exam/rank` → 排行榜

---

### 阶段五：前端类型与 API 层（P0，可与后端并行）

#### 步骤 5.1 — 创建类型定义文件

文件：`frontend/OJ_frontend/src/types/exam.ts`

基于前端文档 §5，注意修复问题：
- 在 `ExamQueryRequest` 中新增 `examId?: number`（修复P8）

#### 步骤 5.2 — 创建接口封装文件

文件：`frontend/OJ_frontend/src/api/exam.ts`

基于前端文档 §6，所有接口路径与后端 `/exam/*` 对应。

---

### 阶段六：前端组件与核心页面（P0）

#### 步骤 6.1 — 创建 `ExamCountdown.vue` 组件

文件：`frontend/OJ_frontend/src/components/ExamCountdown.vue`

**修复问题P7**，将 prop 改为 `targetTime`：
```typescript
const props = defineProps<{ targetTime: string }>()
// 计算剩余秒数
const diffSec = computed(() =>
  Math.max(0, Math.floor((new Date(props.targetTime).getTime() - now.value) / 1000))
)
```

使用场景：
- 考试未开始：`<ExamCountdown :target-time="exam.startTime" />`（倒计时到开始）
- 考试进行中：`<ExamCountdown :target-time="exam.endTime" />`（倒计时到结束）

倒计时颜色规则（剩余秒数）：
- `>= 1800s`（≥30分钟）→ `#262626`
- `600s ~ 1800s`（10~30分钟）→ `#F0AD4E`
- `< 600s`（<10分钟）→ `#EF4743`

#### 步骤 6.2 — 创建 `ExamListView.vue`（考试列表页）

文件：`frontend/OJ_frontend/src/views/exam/ExamListView.vue`

参考前端文档 §7.1，风格对齐 `QuestionsView.vue`：
- 搜索栏：按名称 + 状态筛选
- 表格：名称、时间区间、状态标签（`ExamStatusColor`/`ExamStatusLabel`）、题目数、操作按钮
- 操作按钮根据 `status + joined` 动态显示（前端文档 §7.1 操作按钮逻辑）
- 进行中时时间列内嵌 `ExamCountdown` 显示剩余时间
- 分页：每页10条

#### 步骤 6.3 — 创建 `ExamDetailView.vue`（考试详情/报名页）

文件：`frontend/OJ_frontend/src/views/exam/ExamDetailView.vue`

参考前端文档 §7.2：

**考试未开始布局**（status=0）：
- 基本信息（标题、时间、人数、总分）
- `<ExamCountdown :target-time="exam.startTime" />` 倒计时到开始（修复P7）
- Markdown 渲染考试说明
- 「立即报名」/「已报名」按钮

**考试进行中布局**（status=1，已报名）：
- 顶部：考试名 + `<ExamCountdown :target-time="exam.endTime" />`（修复P7）
- 题目列表表格（从 `ExamVO.questions` 渲染）
- 各题状态：`myAccepted`→已通过 / `myScore=0`→未提交 / 否则→判题中
- **判题中轮询策略**（修复P10）：在 `ExamDetailView` 使用每5s `getExamVO` 刷新（简单），在 `ExamRoomView` 使用精确轮询
- 当前总分（前端 SUM `myScore`）
- 点击题目行跳转 `ExamRoomView`

#### 步骤 6.4 — 创建 `ExamRoomView.vue`（考场做题页）

文件：`frontend/OJ_frontend/src/views/exam/ExamRoomView.vue`

参考前端文档 §7.3，高度复用 `ViewQuestionView.vue`：
- 顶部固定显示：考试名 + 题目序号（X/N）+ `<ExamCountdown :target-time="exam.endTime" />`
- 左侧：题目信息（与 ViewQuestionView 一致）+ 本题得分信息 + 上一题/下一题导航
- 右侧：代码编辑器（复用 `CodeEditor.vue`）+ 提交按钮
- 提交接口改为 `submitInExam()`（传 `examId`）
- 判题轮询：复用 `ViewQuestionView` 的精确轮询逻辑（通过 `questionSubmitId` 查询状态）
- 返回考场按钮：跳转 `/exam/:id`

---

### 阶段七：前端排行榜与管理页（P1）

#### 步骤 7.1 — 创建 `ExamResultView.vue`（考试结果/排行榜页）

文件：`frontend/OJ_frontend/src/views/exam/ExamResultView.vue`

参考前端文档 §7.4：
- 接口：`POST /api/exam/rank`（传 `examId`）
- 表格列：排名（1/2/3 用奖牌 emoji）、用户、得分/总分、完成时间、各题得分徽章
- 各题得分用 `✅/❌` + 分值显示
- 当前用户行：若不在当前页则固定在底部，橙色左边框 + `#FFF7ED` 背景高亮
- 「刷新」手动按钮，不自动轮询
- 分页：每页50条

#### 步骤 7.2 — 创建 `ManageExamView.vue`（管理考试页）

文件：`frontend/OJ_frontend/src/views/exam/ManageExamView.vue`

参考前端文档 §7.5，风格对齐 `ManageQuestionView.vue`：
- 表格列：名称、时间、状态、操作
- 操作按钮逻辑：
  - 未开始 → 「编辑」（跳 `/manage/exam/edit?id=xxx`）+「删除」（确认后逻辑删除）
  - 进行中 → 「编辑」（限定字段）+「取消考试」（弹确认框，`update status=3`）
  - 已结束 → 「查看结果」（跳 `/exam/:id/result`）
  - 已取消 → 灰显，无操作
- 接口：`listExamByPage`（admin 接口）

---

### 阶段八：前端管理员创建/编辑页（P2）

#### 步骤 8.1 — 创建 `AddExamView.vue`（创建/编辑考试页）

文件：`frontend/OJ_frontend/src/views/exam/AddExamView.vue`

参考前端文档 §7.6，风格对齐 `AddQuestionView.vue`：
- 表单字段：名称（必填）、是否公开、开始时间、结束时间（日期时间选择器）
- Markdown 编辑器：考试说明（复用 `MdEditor.vue`）
- 题目列表：可排序、可修改分值、可移除
- 「从题库添加题目」弹窗：复用题目搜索/表格逻辑，支持多选，统一设置分值
- 表单校验：
  - 开始时间 > 当前时间（新建时）
  - 结束时间 > 开始时间
  - 题目列表至少1道
  - 分值范围 1~10000
- 编辑模式（路由含 `?id=xxx`）：先 `getExamVO(id)` 回显，提交调 `updateExam`

#### 步骤 8.2 — 创建 `exam store`（Pinia）

文件：`frontend/OJ_frontend/src/store/exam.ts`

参考前端文档 §9，在考场内全局共享当前考试信息（`ExamDetailView` 存入，`ExamRoomView` 读取），避免重复请求。

---

### 阶段九：路由与导航配置（P1）

#### 步骤 9.1 — 更新路由配置

文件：`frontend/OJ_frontend/src/router/routes.ts`

**以第3.2节为准**（有 `name` 字段，修复P9，避免两处不一致），将以下路由追加到 `BasicLayout` 的 children 数组中：

```typescript
{ path: '/exams', name: 'ExamList', component: () => import('@/views/exam/ExamListView.vue'),
  meta: { requiresRole: 'user', title: '参加考试', showInMenu: true } },
{ path: '/exam/:id', name: 'ExamDetail', component: () => import('@/views/exam/ExamDetailView.vue'),
  meta: { requiresRole: 'user' } },
{ path: '/exam/:examId/problem/:questionId', name: 'ExamRoom',
  component: () => import('@/views/exam/ExamRoomView.vue'), meta: { requiresRole: 'user' } },
{ path: '/exam/:id/result', name: 'ExamResult',
  component: () => import('@/views/exam/ExamResultView.vue'), meta: { requiresRole: 'user' } },
{ path: '/manage/exam', name: 'ManageExam',
  component: () => import('@/views/exam/ManageExamView.vue'),
  meta: { requiresRole: 'admin', title: '管理考试', showInMenu: true } },
{ path: '/manage/exam/add', name: 'AddExam',
  component: () => import('@/views/exam/AddExamView.vue'), meta: { requiresRole: 'admin' } },
{ path: '/manage/exam/edit', name: 'EditExam',
  component: () => import('@/views/exam/AddExamView.vue'), meta: { requiresRole: 'admin' } }
```

#### 步骤 9.2 — 更新 `BasicLayout.vue` 导航栏

文件：`frontend/OJ_frontend/src/layouts/BasicLayout.vue`

在「提交记录」之后插入「参加考试」；管理员菜单在「管理题目」之后增加「管理考试」。

新菜单结构（参考前端文档 §10）：
```
[ Logo XI OJ ]  [ 浏览题目 ]  [ 提交记录 ]  [ 参加考试 ]  [ 创建题目 ]  [ 头像 ▾ ]
                                                              ↓（管理员下拉）
                                                   [ 管理题目 ] [ 管理用户 ] [ 管理考试 ]
```

---

### 阶段十：测试与验收（P2）

#### 步骤 10.1 — 后端单元/集成测试

文件：`src/test/java/com/XI/xi_oj/judge/codesandbox/CodeSandboxTest.java`（参考已有测试）

重点测试场景（参考后端文档 §12）：

| 测试场景 | 验证点 |
|---------|-------|
| 创建考试 + 添加题目 | `exam` 和 `exam_question` 表正确写入 |
| 定时任务触发 | 模拟时间推进，验证 `exam.status` 自动变更 |
| 用户报名 | `exam_record` 写入，重复报名触发 UNIQUE 约束 |
| 考场提交 → 判题完成 | `exam_submission.score` 和 `exam_record.total_score` 正确更新 |
| 排行榜查询 | RANK() OVER 返回正确排名顺序 |
| 考试已结束时提交 | 返回 `EXAM_ENDED` 错误码 |

#### 步骤 10.2 — 前后端联调

1. 管理员流程：创建考试 → 添加题目 → 发布（验证状态变更）
2. 用户流程：查看列表 → 报名 → 进入考场 → 提交代码 → 查看得分
3. 排行榜：考试结束后验证排名计算正确性
4. 边界验证：考试时间结束后提交被拒绝、重复报名被拒绝

---

## 三、执行顺序总览

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

## 四、开发优先级汇总

| 优先级 | 任务 | 所属阶段 |
|--------|------|---------|
| P0 | 建4张表 | 阶段一 |
| P0 | 4个实体类 + Mapper | 阶段二 |
| P0 | DTO/VO/ErrorCode（含修复文档问题） | 阶段二 |
| P0 | ExamService（含题目管理补充方法） | 阶段三 |
| P0 | ExamStatusScheduler + @EnableScheduling | 阶段三 |
| P0 | ExamRecordService（报名 + 提交 + status修复） | 阶段三 |
| P0 | `src/types/exam.ts` + `src/api/exam.ts` | 阶段五 |
| P0 | `ExamCountdown.vue`（修复prop命名） | 阶段六 |
| P0 | `ExamListView.vue` | 阶段六 |
| P0 | `ExamDetailView.vue` | 阶段六 |
| P0 | `ExamRoomView.vue` | 阶段六 |
| P1 | ExamSubmissionService（onJudgeComplete + 排行榜） | 阶段三 |
| P1 | ExamController（完整实现） | 阶段四 |
| P1 | 扩展 JudgeServiceImpl | 阶段四 |
| P1 | `ExamResultView.vue` | 阶段七 |
| P1 | `ManageExamView.vue` | 阶段七 |
| P1 | 路由 + 导航栏更新 | 阶段九 |
| P2 | `AddExamView.vue` | 阶段八 |
| P2 | exam store（Pinia） | 阶段八 |
| P2 | 测试 | 阶段十 |

---

*文档版本：V1.0 | 生成日期：2026-04-10*
