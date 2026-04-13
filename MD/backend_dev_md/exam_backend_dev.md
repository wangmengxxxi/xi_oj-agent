# XI OJ 考试功能 — 后端开发文档

> 版本：V1.0 | 日期：2026-04-09
> 基准分支：main | 依赖版本：Spring Boot 3.3.6 / Java 21 / MyBatis-Plus 3.5.11

---

## 一、功能概述

考试功能在现有 OJ 题目体系之上增加**有时限的组合考察**能力：

| 角色 | 核心操作 |
|------|---------|
| 管理员 | 创建/编辑/删除考试，向考试添加/移除题目，设置各题分值，查看全量参赛结果与排行榜 |
| 普通用户 | 查看考试列表，报名参加考试，在考场内提交代码，查看自己的考试结果与公开排行榜 |

核心流程：
```
管理员创建考试 → 添加题目 → 考试开放后用户报名 → 用户进入考场提交代码
→ 判题完成后自动记录得分 → 考试结束后生成排行榜
```

---

## 二、技术选型

完全基于现有依赖，**无需引入新的第三方库**：

| 选项 | 选型 | 说明 |
|------|------|------|
| 持久层 ORM | MyBatis-Plus 3.5.11 | 与现有实体一致，使用 `IService` + `ServiceImpl` |
| 接口文档 | Knife4j 4.5.0 | 自动同步至 `/api/doc.html` |
| 参数校验 | Spring Boot Validation（已引入） | `@NotNull`, `@NotBlank` 注解校验 |
| 时间处理 | Java 21 `LocalDateTime` + `DateTimeUtil`（Hutool） | Hutool 5.8.26 已引入 |
| 考试状态管理 | Spring `@Scheduled` 定时任务 | 每 30 秒扫库更新 `exam.status`，状态以数据库值为准；提交接口直接读 DB status 做拦截 |
| 排行榜计算 | 数据库聚合查询 | 实时从 `exam_submission` 聚合，无缓存（考试期间流量可控） |
| 权限控制 | 现有 `@AuthCheck` AOP 注解 | `mustRole = "admin"` 限制管理接口 |
| 响应封装 | 现有 `BaseResponse<T>` + `ResultUtils` | 保持与现有接口完全一致 |

### 2.1 定时任务选型分析：`@Scheduled` vs Quartz

选择 Spring `@Scheduled` 而非 Quartz，理由如下：

**资源开销**

`@Scheduled` 底层是一个定时线程，30 秒触发一次后立即休眠，不触发时零 CPU 占用。实际执行内容只有两条走索引的批量 UPDATE，毫秒级完成，不存在资源压力。类比 MySQL 自身的后台检查线程——定时唤醒、执行、休眠，这是标准后台调度模式。

**两者对比**

| 维度 | `@Scheduled` | Quartz |
|------|-------------|--------|
| 依赖 | Spring Boot 内置，零成本 | 需引入依赖 + 额外建 11 张系统表 |
| 配置复杂度 | 两个注解即可 | 需配置 `JobDetail`、`Trigger`、`Scheduler` |
| 分布式安全 | 多实例会重复执行（但 UPDATE 幂等，无影响） | JDBC Store 保证集群内只有一个节点执行 |
| 任务持久化 | 不持久，重启后自动恢复（无需持久化） | 持久化到数据库，重启可恢复未完成任务 |
| 动态调整调度 | 需重启服务 | 可运行时修改 cron |
| 适用规模 | 单实例 / 小型项目 | 分布式集群 / 企业级调度 |

**为何 `@Scheduled` 足够**

1. **UPDATE 操作天然幂等**：`WHERE status=0 AND start_time <= NOW()` 的条件决定了多次执行结果相同，即使多实例重复执行也不会出错
2. **当前项目单实例部署**，Quartz 解决的分布式问题不存在
3. **任务无需持久化**：服务重启后定时任务自动恢复运行，不存在"遗漏状态变更"的场景

**未来扩展路径**

若后续扩展为多实例集群部署，无需迁移到 Quartz，直接在现有任务上加 Redis 分布式锁即可（项目已有 Redis 依赖）：

```java
@Scheduled(fixedRate = 30_000)
public void syncExamStatus() {
    Boolean locked = redisTemplate.opsForValue()
        .setIfAbsent("lock:exam:status:sync", "1", 25, TimeUnit.SECONDS);
    if (!Boolean.TRUE.equals(locked)) return; // 其他实例已在执行
    doSync();
}
```

---

## 三、数据库设计

### 3.1 新增表总览

| 表名 | 用途 |
|------|------|
| `exam` | 考试基本信息 |
| `exam_question` | 考试与题目的关联关系（多对多，含分值） |
| `exam_record` | 用户参加考试的报名/参赛记录（一人一考一条） |
| `exam_submission` | 考场内每道题的最优提交记录（一人一题一条，持续更新） |

> 设计原则：**不修改现有表结构**（`question_submit` 保持原样），通过新表 `exam_submission` 引用 `question_submit.id` 关联考场提交。

---

### 3.2 DDL — `exam`（考试主表）

```sql
CREATE TABLE `exam`
(
    `id`             bigint       NOT NULL AUTO_INCREMENT COMMENT '考试 ID',
    `title`          varchar(256) NOT NULL COMMENT '考试名称',
    `description`    text         NULL COMMENT '考试说明（Markdown）',
    `start_time`     datetime     NOT NULL COMMENT '考试开始时间',
    `end_time`       datetime     NOT NULL COMMENT '考试结束时间',
    `status`         tinyint      NOT NULL DEFAULT 0 COMMENT '0=未开始 1=进行中 2=已结束 3=已取消',
    `is_public`      tinyint      NOT NULL DEFAULT 1 COMMENT '是否公开报名：1=公开 0=禁止报名',
    `create_user_id` bigint       NOT NULL COMMENT '创建者用户 ID（管理员）',
    `create_time`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_delete`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=已删除',
    PRIMARY KEY (`id`),
    INDEX `idx_status` (`status`),
    INDEX `idx_start_time` (`start_time`),
    INDEX `idx_create_user_id` (`create_user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '考试表';
```

**status 说明（由定时任务写库，以 DB 值为权威）：**
- `0` 未开始：初始值，定时任务检测到 `NOW() >= start_time` 时改为 1
- `1` 进行中：定时任务检测到 `NOW() > end_time` 时改为 2
- `2` 已结束：终态，不再变化
- `3` 已取消：管理员手动标记，不随时间改变

> **所有写操作（提交代码、报名）均以数据库中的 `status` 字段为准**，不在应用层重新计算，确保考试时间截止后后端能强制拒绝提交。

---

### 3.3 DDL — `exam_question`（考试题目关联表）

```sql
CREATE TABLE `exam_question`
(
    `id`          bigint  NOT NULL AUTO_INCREMENT COMMENT '关联 ID',
    `exam_id`     bigint  NOT NULL COMMENT '考试 ID',
    `question_id` bigint  NOT NULL COMMENT '题目 ID',
    `score`       int     NOT NULL DEFAULT 100 COMMENT '该题满分分值',
    `sort_order`  int     NOT NULL DEFAULT 0 COMMENT '题目在考试中的显示顺序（升序）',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_delete`   tinyint NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_exam_question` (`exam_id`, `question_id`, `is_delete`),
    INDEX `idx_exam_id` (`exam_id`),
    INDEX `idx_question_id` (`question_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '考试题目关联表';
```

---

### 3.4 DDL — `exam_record`（用户参赛记录表）

```sql
CREATE TABLE `exam_record`
(
    `id`          bigint   NOT NULL AUTO_INCREMENT COMMENT '记录 ID',
    `exam_id`     bigint   NOT NULL COMMENT '考试 ID',
    `user_id`     bigint   NOT NULL COMMENT '用户 ID',
    `status`      tinyint  NOT NULL DEFAULT 0 COMMENT '0=已报名 1=考试中 2=已结束',
    `total_score` int      NOT NULL DEFAULT 0 COMMENT '最终得分（考试结束后汇总）',
    `finish_time` datetime NULL COMMENT '用户最后一次提交的时间（用于同分排名）',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_delete`   tinyint  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_exam_user` (`exam_id`, `user_id`),
    INDEX `idx_exam_id` (`exam_id`),
    INDEX `idx_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '用户参赛记录表';
```

---

### 3.5 DDL — `exam_submission`（考场最优提交记录表）

```sql
CREATE TABLE `exam_submission`
(
    `id`                 bigint  NOT NULL AUTO_INCREMENT COMMENT '记录 ID',
    `exam_id`            bigint  NOT NULL COMMENT '考试 ID',
    `exam_record_id`     bigint  NOT NULL COMMENT '参赛记录 ID（关联 exam_record）',
    `question_id`        bigint  NOT NULL COMMENT '题目 ID',
    `user_id`            bigint  NOT NULL COMMENT '用户 ID',
    `question_submit_id` bigint  NULL COMMENT '最优提交记录 ID（关联 question_submit）',
    `score`              int     NOT NULL DEFAULT 0 COMMENT '该题已获得分值（0 或满分）',
    `is_accepted`        tinyint NOT NULL DEFAULT 0 COMMENT '是否已通过：0=否 1=是',
    `create_time`        datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`        datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_delete`          tinyint NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_exam_user_question` (`exam_id`, `user_id`, `question_id`),
    INDEX `idx_exam_id` (`exam_id`),
    INDEX `idx_exam_record_id` (`exam_record_id`),
    INDEX `idx_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '考场最优提交记录表（每用户每题一条，通过则更新得分）';
```

---

### 3.6 表关系说明

```
exam  1──n  exam_question  n──1  question
 │
 1──n  exam_record  1──n  exam_submission  n──1  question_submit
          │
          n──1  user
```

---

## 四、实体类设计

遵循现有实体规范：`@TableName`、`@TableId(type = IdType.AUTO)`、`@TableLogic`、`@Data`（Lombok）。

### 4.1 `Exam.java`

```java
// 包路径：com.XI.xi_oj.model.entity
@Data
@TableName("exam")
public class Exam implements Serializable {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String description;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /**
     * 0=未开始 1=进行中 2=已结束 3=已取消
     * 由 ExamStatusScheduler 每 30 秒自动更新（0→1→2），3 由管理员手动写入
     */
    private Integer status;
    private Integer isPublic;
    private Long createUserId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic
    private Integer isDelete;
}
```

### 4.2 `ExamQuestion.java`

```java
@Data
@TableName("exam_question")
public class ExamQuestion implements Serializable {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long examId;
    private Long questionId;
    private Integer score;
    private Integer sortOrder;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic
    private Integer isDelete;
}
```

### 4.3 `ExamRecord.java`

```java
@Data
@TableName("exam_record")
public class ExamRecord implements Serializable {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long examId;
    private Long userId;
    /** 0=已报名 1=考试中 2=已结束 */
    private Integer status;
    private Integer totalScore;
    private LocalDateTime finishTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic
    private Integer isDelete;
}
```

### 4.4 `ExamSubmission.java`

```java
@Data
@TableName("exam_submission")
public class ExamSubmission implements Serializable {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long examId;
    private Long examRecordId;
    private Long questionId;
    private Long userId;
    private Long questionSubmitId;
    private Integer score;
    private Integer isAccepted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic
    private Integer isDelete;
}
```

---

## 五、DTO 与 VO 设计

### 5.1 请求 DTO

**`ExamAddRequest`**（创建考试）

```java
@Data
public class ExamAddRequest implements Serializable {
    @NotBlank(message = "考试名称不能为空")
    private String title;
    private String description;
    @NotNull(message = "开始时间不能为空")
    private LocalDateTime startTime;
    @NotNull(message = "结束时间不能为空")
    private LocalDateTime endTime;
    /** 是否公开报名，默认 1 */
    private Integer isPublic = 1;
}
```

**`ExamUpdateRequest`**（更新考试，管理员）

```java
@Data
public class ExamUpdateRequest implements Serializable {
    @NotNull
    private Long id;
    private String title;
    private String description;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer isPublic;
    /** 仅允许设置为 3（取消），其余状态自动计算 */
    private Integer status;
}
```

**`ExamQueryRequest`**（分页查询考试列表 / 排行榜）

```java
@Data
public class ExamQueryRequest implements Serializable {
    private String title;
    /** 0=未开始 1=进行中 2=已结束 */
    private Integer status;
    /** 考试 ID，排行榜接口（/api/exam/rank）使用 */
    private Long examId;
    private long current = 1;
    private long pageSize = 10;
}
```

**`ExamQuestionAddRequest`**（向考试添加题目）

```java
@Data
public class ExamQuestionAddRequest implements Serializable {
    @NotNull
    private Long examId;
    @NotNull
    private Long questionId;
    /** 该题分值，默认 100 */
    private Integer score = 100;
    /** 题目排序，默认追加到末尾 */
    private Integer sortOrder;
}
```

**`ExamQuestionDeleteRequest`**（从考试移除题目）

```java
@Data
public class ExamQuestionDeleteRequest implements Serializable {
    @NotNull
    private Long examId;
    @NotNull
    private Long questionId;
}
```

**`ExamQuestionUpdateRequest`**（更新题目分值/排序）

```java
@Data
public class ExamQuestionUpdateRequest implements Serializable {
    @NotNull
    private Long examId;
    @NotNull
    private Long questionId;
    /** 该题分值，1~10000 */
    private Integer score;
    /** 题目在考试中的显示顺序 */
    private Integer sortOrder;
}
```

**`ExamJoinRequest`**（用户报名考试）

```java
@Data
public class ExamJoinRequest implements Serializable {
    @NotNull(message = "考试 ID 不能为空")
    private Long examId;
}
```

**`ExamCodeSubmitRequest`**（考场内提交代码）

```java
@Data
public class ExamCodeSubmitRequest implements Serializable {
    @NotNull
    private Long examId;
    @NotNull
    private Long questionId;
    @NotBlank
    private String language;
    @NotBlank
    private String code;
}
```

---

### 5.2 响应 VO

**`ExamVO`**（考试视图，用于列表和详情）

```java
@Data
public class ExamVO implements Serializable {
    private Long id;
    private String title;
    private String description;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /**
     * 直接取自 exam.status，由定时任务维护，无需应用层重新计算
     * 0=未开始 1=进行中 2=已结束 3=已取消
     */
    private Integer status;
    private Integer isPublic;
    /** 题目数量 */
    private Integer questionCount;
    /** 考试总分（各题分值之和） */
    private Integer totalScore;
    /** 当前报名人数 */
    private Integer participantCount;
    /** 当前用户是否已报名（需登录时填充） */
    private Boolean joined;
    /** 考试题目列表（仅详情接口返回，且仅考试期间/结束后返回） */
    private List<ExamQuestionVO> questions;
    private Long createUserId;
    private LocalDateTime createTime;
    // 不再需要 computeStatus()，status 直接来自数据库
}
```

**`ExamQuestionVO`**（考试内题目视图）

```java
@Data
public class ExamQuestionVO implements Serializable {
    private Long id;          // exam_question.id
    private Long questionId;
    private String title;
    private List<String> tags;
    private Integer submitNum;
    private Integer acceptedNum;
    private Integer score;    // 该题在考试中的满分
    private Integer sortOrder;
    /** 判题配置（时间/内存限制） */
    private JudgeConfig judgeConfig;
    /** 当前用户在该题的得分（已报名且进入考场时填充） */
    private Integer myScore;
    /** 当前用户是否已通过该题 */
    private Boolean myAccepted;
}
```

**`ExamRecordVO`**（参赛记录视图，用于排行榜）

```java
@Data
public class ExamRecordVO implements Serializable {
    private Long id;
    private Long examId;
    private Long userId;
    private String userName;
    private String userAvatar;
    private Integer totalScore;
    private LocalDateTime finishTime;
    /** 排名（由查询层计算） */
    private Integer rank;
    /** 各题得分详情（List，顺序对应 exam_question.sort_order） */
    private List<QuestionScoreVO> questionScores;
}
```

**`QuestionScoreVO`**（排行榜中每题得分）

```java
@Data
public class QuestionScoreVO implements Serializable {
    private Long questionId;
    private Integer score;
    private Boolean isAccepted;
}
```

---

## 六、接口设计

所有接口均遵循现有规范：`BaseResponse<T>` 包装，路径前缀 `/api/exam`。

### 6.1 用户端接口

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| `POST` | `/api/exam/list/page/vo` | 允许未登录（未登录时 `joined` 字段为 null） | 分页获取考试列表（ExamVO，不含题目详情） |
| `GET` | `/api/exam/get/vo` | 需登录 | 获取考试详情（含题目列表，进行中/已结束才返回题目） |
| `POST` | `/api/exam/join` | 需登录 | 报名考试（创建 exam_record） |
| `POST` | `/api/exam/submit` | 需登录 | 考场内提交代码 |
| `GET` | `/api/exam/my/record` | 需登录 | 获取当前用户的参赛记录（`?examId=xxx`） |
| `POST` | `/api/exam/rank` | 需登录 | 获取考试排行榜（ExamRecordVO 列表，分页） |

### 6.2 管理员接口

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| `POST` | `/api/exam/add` | admin | 创建考试 |
| `POST` | `/api/exam/update` | admin | 更新考试信息（含取消） |
| `POST` | `/api/exam/delete` | admin | 删除考试（逻辑删除） |
| `POST` | `/api/exam/list/page` | admin | 管理员分页获取完整考试列表 |
| `POST` | `/api/exam/question/add` | admin | 向考试添加题目 |
| `POST` | `/api/exam/question/delete` | admin | 从考试移除题目 |
| `POST` | `/api/exam/question/update` | admin | 更新题目分值/排序 |
| `GET` | `/api/exam/result/list` | admin | 获取考试所有参赛者结果（`?examId=xxx`） |

---

### 6.3 接口详细说明

#### `POST /api/exam/list/page/vo`

**请求体**（`ExamQueryRequest`）：
```json
{
  "title": "期中",
  "status": 1,
  "current": 1,
  "pageSize": 10
}
```

**响应**（`BaseResponse<Page<ExamVO>>`）：
```json
{
  "code": 0,
  "data": {
    "records": [
      {
        "id": 1,
        "title": "2026年春季期中考试",
        "startTime": "2026-04-10T09:00:00",
        "endTime": "2026-04-10T11:00:00",
        "status": 1,
        "questionCount": 5,
        "totalScore": 500,
        "participantCount": 32,
        "joined": true
      }
    ],
    "total": 1, "current": 1, "size": 10
  },
  "message": "ok"
}
```

---

#### `GET /api/exam/get/vo?id=1`

- 考试状态为 `0`（未开始）时：返回基本信息，**不返回题目列表**（防止提前看到题目）
- 考试状态为 `1`（进行中）或 `2`（已结束）时：返回完整题目列表（含各题描述为空，题目内容需用户到做题页单独获取）
- `questions` 列表中的 `myScore` / `myAccepted` 仅当用户已报名时填充

---

#### `POST /api/exam/join`

```json
{ "examId": 1 }
```

**校验逻辑**：
1. 考试必须为 `is_public=1` 且状态为 `0`（未开始）或 `1`（进行中）
2. 用户未重复报名（`exam_record` 表 UNIQUE 约束保证）
3. 插入 `exam_record`（status=0）

**响应**：`BaseResponse<Long>`（examRecordId）

---

#### `POST /api/exam/submit`

**请求体**（`ExamCodeSubmitRequest`）：
```json
{
  "examId": 1,
  "questionId": 101,
  "language": "java",
  "code": "class Solution { ... }"
}
```

**服务层处理逻辑**：
1. 校验：考试进行中（status=1），用户已报名，questionId 属于该考试的题目
2. 兜底时间校验（防定时任务30秒延迟窗口）：`LocalDateTime.now().isBefore(exam.getEndTime())`，否则抛 `EXAM_ENDED`
3. 调用现有 `QuestionSubmitService.doQuestionSubmit()`，传入 `questionId + language + code`，获得 `questionSubmitId`
4. 在 `exam_submission` 中 upsert 记录（exam_id + user_id + question_id 为 UNIQUE KEY）：
   - 若不存在：INSERT，score=0, is_accepted=0
   - 若已存在：仅更新 `question_submit_id`（指向最新提交，由判题完成后更新得分）
5. 更新 `exam_record`：
   - `finish_time = NOW()`
   - `status = 1`（考试中，首次提交时从0变为1；已为1时幂等更新）

**响应**：`BaseResponse<Long>`（questionSubmitId，前端用此 ID 轮询判题状态，复用现有轮询逻辑）

---

#### 判题完成后的回调（扩展现有 `JudgeServiceImpl`）

在 `JudgeServiceImpl.doJudge()` 结尾追加（通过 `ExamSubmissionService` 注入）：

```java
// 判题完成后，检查是否属于考场提交，若是则更新 exam_submission
examSubmissionService.onJudgeComplete(questionSubmitId, questionSubmit);
```

**`onJudgeComplete` 逻辑**：
1. 查询 `exam_submission` WHERE `question_submit_id = questionSubmitId`
2. 若找到记录且本次结果为 Accepted（且当前 is_accepted=0）：
   - 查找 `exam_question.score` 获得满分
   - 更新 `exam_submission.score = 满分, is_accepted = 1`
   - 重新汇总 `exam_record.total_score = SUM(exam_submission.score) WHERE exam_record_id=xxx`

---

#### `POST /api/exam/rank`

**请求体**：
```json
{ "examId": 1, "current": 1, "pageSize": 50 }
```

**查询 SQL（MyBatis-Plus + 自定义 Mapper）**：
```sql
SELECT er.id, er.user_id, er.total_score, er.finish_time,
       u.user_name, u.user_avatar,
       RANK() OVER (ORDER BY er.total_score DESC, er.finish_time ASC) AS `rank`
FROM exam_record er
JOIN user u ON er.user_id = u.id
WHERE er.exam_id = #{examId} AND er.is_delete = 0
ORDER BY er.total_score DESC, er.finish_time ASC
LIMIT #{offset}, #{pageSize}
```

> 排名规则：总分高者优先；同分则最后一次提交（finish_time）早者优先（鼓励尽快完成）。

---

## 七、服务层设计

### 7.1 包结构

```
com.XI.xi_oj
├── controller
│   └── ExamController.java
├── service
│   ├── ExamService.java
│   ├── ExamQuestionService.java
│   ├── ExamRecordService.java
│   ├── ExamSubmissionService.java
│   └── impl
│       ├── ExamServiceImpl.java
│       ├── ExamQuestionServiceImpl.java
│       ├── ExamRecordServiceImpl.java
│       └── ExamSubmissionServiceImpl.java
├── mapper
│   ├── ExamMapper.java
│   ├── ExamQuestionMapper.java
│   ├── ExamRecordMapper.java
│   └── ExamSubmissionMapper.java
└── model
    ├── entity
    │   ├── Exam.java
    │   ├── ExamQuestion.java
    │   ├── ExamRecord.java
    │   └── ExamSubmission.java
    ├── dto
    │   └── exam/
    │       ├── ExamAddRequest.java
    │       ├── ExamUpdateRequest.java
    │       ├── ExamQueryRequest.java
    │       ├── ExamQuestionAddRequest.java
    │       ├── ExamQuestionDeleteRequest.java
    │       ├── ExamQuestionUpdateRequest.java
    │       ├── ExamJoinRequest.java
    │       └── ExamCodeSubmitRequest.java
    └── vo
        ├── ExamVO.java
        ├── ExamQuestionVO.java
        ├── ExamRecordVO.java
        └── QuestionScoreVO.java
```

---

### 7.2 `ExamService` 关键方法

```java
public interface ExamService extends IService<Exam> {
    /** 创建考试（管理员），返回新考试 ID */
    long addExam(ExamAddRequest request, User loginUser);

    /** 更新考试（管理员） */
    void updateExam(ExamUpdateRequest request, User loginUser);

    /** 逻辑删除考试（管理员）*/
    void deleteExam(long examId, User loginUser);

    /** 分页获取考试列表（用户端，仅公开考试）*/
    Page<ExamVO> listExamVOByPage(ExamQueryRequest request, User loginUser);

    /** 管理员分页获取完整考试列表（含草稿/取消）*/
    Page<ExamVO> listExamByPageForAdmin(ExamQueryRequest request);

    /** 获取考试详情 VO（含实时状态 + 题目列表）*/
    ExamVO getExamVO(long examId, User loginUser);

    /** 将 Exam 实体转换为 VO，填充实时状态、题目数量、报名人数、joined 字段 */
    ExamVO toVO(Exam exam, User loginUser);

    // ===== 考试题目管理（由 ExamQuestionService 委托实现）=====

    /** 向考试添加题目（管理员），要求考试状态为未开始 */
    void addExamQuestion(ExamQuestionAddRequest request);

    /** 从考试移除题目（管理员），逻辑删除 exam_question 记录 */
    void deleteExamQuestion(ExamQuestionDeleteRequest request);

    /** 更新考试题目的分值或排序（管理员） */
    void updateExamQuestion(ExamQuestionUpdateRequest request);
}
```

### 7.3 `ExamRecordService` 关键方法

```java
public interface ExamRecordService extends IService<ExamRecord> {
    /** 用户报名考试，返回 examRecordId */
    long joinExam(ExamJoinRequest request, User loginUser);

    /** 考场内提交代码，返回 questionSubmitId */
    long submitInExam(ExamCodeSubmitRequest request, User loginUser);

    /** 获取当前用户对某场考试的 ExamRecord */
    ExamRecord getMyRecord(long examId, User loginUser);
}
```

### 7.4 `ExamSubmissionService` 关键方法

```java
public interface ExamSubmissionService extends IService<ExamSubmission> {
    /**
     * 判题完成回调：若该 questionSubmitId 关联了考场提交，
     * 则在通过时更新 exam_submission.score 并汇总 exam_record.total_score
     */
    void onJudgeComplete(long questionSubmitId, QuestionSubmit questionSubmit);

    /** 获取用户在某场考试的各题得分详情 */
    List<QuestionScoreVO> getMyScores(long examId, long userId);

    /** 获取考试排行榜（分页，带排名计算）*/
    Page<ExamRecordVO> getRankPage(long examId, long current, long pageSize);
}
```

---

## 八、`ExamController` 骨架

```java
@RestController
@RequestMapping("/exam")
@Slf4j
@Tag(name = "考试接口")
public class ExamController {

    @Resource private ExamService examService;
    @Resource private ExamRecordService examRecordService;
    @Resource private ExamSubmissionService examSubmissionService;
    @Resource private UserService userService;

    // ===== 管理员接口 =====

    @PostMapping("/add")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Long> addExam(@RequestBody @Validated ExamAddRequest request,
                                      HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        long examId = examService.addExam(request, loginUser);
        return ResultUtils.success(examId);
    }

    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateExam(@RequestBody @Validated ExamUpdateRequest request,
                                            HttpServletRequest httpRequest) {
        examService.updateExam(request, userService.getLoginUser(httpRequest));
        return ResultUtils.success(true);
    }

    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteExam(@RequestBody @Validated DeleteRequest request,
                                            HttpServletRequest httpRequest) {
        examService.deleteExam(request.getId(), userService.getLoginUser(httpRequest));
        return ResultUtils.success(true);
    }

    @PostMapping("/list/page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<ExamVO>> listExamByPage(@RequestBody ExamQueryRequest request) {
        return ResultUtils.success(examService.listExamByPageForAdmin(request));
    }

    @PostMapping("/question/add")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> addExamQuestion(@RequestBody @Validated ExamQuestionAddRequest request) {
        examService.addExamQuestion(request);
        return ResultUtils.success(true);
    }

    @PostMapping("/question/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteExamQuestion(@RequestBody @Validated ExamQuestionDeleteRequest request) {
        examService.deleteExamQuestion(request);
        return ResultUtils.success(true);
    }

    @GetMapping("/result/list")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<ExamRecordVO>> getResultList(@RequestParam Long examId,
                                                          @RequestParam(defaultValue = "1") long current,
                                                          @RequestParam(defaultValue = "50") long pageSize) {
        return ResultUtils.success(examSubmissionService.getRankPage(examId, current, pageSize));
    }

    // ===== 用户端接口 =====

    @PostMapping("/list/page/vo")
    public BaseResponse<Page<ExamVO>> listExamVOByPage(@RequestBody ExamQueryRequest request,
                                                       HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUserPermitNull(httpRequest);
        return ResultUtils.success(examService.listExamVOByPage(request, loginUser));
    }

    @GetMapping("/get/vo")
    public BaseResponse<ExamVO> getExamVO(@RequestParam Long id, HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUserPermitNull(httpRequest);
        return ResultUtils.success(examService.getExamVO(id, loginUser));
    }

    @PostMapping("/join")
    public BaseResponse<Long> joinExam(@RequestBody @Validated ExamJoinRequest request,
                                       HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        return ResultUtils.success(examRecordService.joinExam(request, loginUser));
    }

    @PostMapping("/submit")
    public BaseResponse<Long> submitInExam(@RequestBody @Validated ExamCodeSubmitRequest request,
                                           HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        return ResultUtils.success(examRecordService.submitInExam(request, loginUser));
    }

    @GetMapping("/my/record")
    public BaseResponse<ExamRecord> getMyRecord(@RequestParam Long examId,
                                                HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        return ResultUtils.success(examRecordService.getMyRecord(examId, loginUser));
    }

    @PostMapping("/rank")
    public BaseResponse<Page<ExamRecordVO>> getRank(@RequestBody ExamQueryRequest request) {
        return ResultUtils.success(
            examSubmissionService.getRankPage(request.getExamId(),
                                             request.getCurrent(), request.getPageSize()));
    }
}
```

---

## 九、考试状态定时任务（`ExamStatusScheduler`）

### 9.1 启用调度（主启动类）

在 Spring Boot 主类上添加注解（若未开启）：

```java
@SpringBootApplication
@EnableScheduling   // 新增这一行
public class XiOjApplication { ... }
```

### 9.2 调度器实现

```java
// 包路径：com.XI.xi_oj.job
@Component
@Slf4j
public class ExamStatusScheduler {

    @Resource
    private ExamService examService;

    /**
     * 每 30 秒执行一次，批量更新考试状态
     * 最大延迟 30 秒：例如 09:00:00 开考，最晚 09:00:30 状态变为「进行中」
     */
    @Scheduled(fixedRate = 30_000)
    public void syncExamStatus() {
        LocalDateTime now = LocalDateTime.now();

        // 未开始 → 进行中：start_time 已到且尚未标记为进行中
        examService.lambdaUpdate()
            .eq(Exam::getStatus, 0)
            .le(Exam::getStartTime, now)
            .eq(Exam::getIsDelete, 0)
            .set(Exam::getStatus, 1)
            .update();

        // 进行中 → 已结束：end_time 已过且尚未标记为已结束
        examService.lambdaUpdate()
            .eq(Exam::getStatus, 1)
            .lt(Exam::getEndTime, now)
            .eq(Exam::getIsDelete, 0)
            .set(Exam::getStatus, 2)
            .update();

        log.debug("[ExamStatusScheduler] 执行完毕，时间={}", now);
    }
}
```

### 9.3 任务频率说明

| 频率 | 最大状态延迟 | 适用场景 |
|------|------------|---------|
| 每 30 秒 | 30 秒 | 推荐，对考试开始/结束时间精度已足够 |
| 每 60 秒 | 60 秒 | 如担心数据库压力可放宽 |
| 每 10 秒 | 10 秒 | 对时间精度要求极高时使用 |

> **注意**：30 秒延迟意味着考试结束时，用户在最坏情况下还能多提交 30 秒。若业务上不可接受，可将频率改为 `fixedRate = 10_000`，或在提交接口中额外用 `end_time` 做一次兜底校验：
>
> ```java
> // ExamRecordServiceImpl.submitInExam() 中的兜底校验
> if (exam.getStatus() != 1 || LocalDateTime.now().isAfter(exam.getEndTime())) {
>     throw new BusinessException(ErrorCode.EXAM_ENDED, "考试已结束，无法提交");
> }
> ```

---

## 十、现有 `JudgeServiceImpl` 扩展点

在 `JudgeServiceImpl.doJudge()` 方法末尾（更新 `QuestionSubmit` 状态之后）追加：

```java
// 第七步：若属于考场提交，更新考场得分
try {
    examSubmissionService.onJudgeComplete(questionSubmitId, questionSubmit);
} catch (Exception e) {
    // 不影响判题主流程，仅记录日志
    log.error("考场得分更新失败，questionSubmitId={}", questionSubmitId, e);
}
```

**`onJudgeComplete` 实现要点**：

```java
@Override
@Transactional
public void onJudgeComplete(long questionSubmitId, QuestionSubmit questionSubmit) {
    // 1. 查询是否有关联考场提交记录
    ExamSubmission submission = lambdaQuery()
        .eq(ExamSubmission::getQuestionSubmitId, questionSubmitId)
        .one();
    if (submission == null || submission.getIsAccepted() == 1) return; // 已通过则不再更新

    // 2. 判断本次是否 Accepted
    JudgeInfo judgeInfo = JSONUtil.toBean(questionSubmit.getJudgeInfo(), JudgeInfo.class);
    boolean accepted = "Accepted".equals(judgeInfo.getMessage());
    if (!accepted) return;

    // 3. 查询该题在考试中的满分
    ExamQuestion examQuestion = examQuestionService.lambdaQuery()
        .eq(ExamQuestion::getExamId, submission.getExamId())
        .eq(ExamQuestion::getQuestionId, submission.getQuestionId())
        .one();
    int fullScore = examQuestion != null ? examQuestion.getScore() : 0;

    // 4. 更新 exam_submission 得分
    lambdaUpdate()
        .eq(ExamSubmission::getId, submission.getId())
        .set(ExamSubmission::getScore, fullScore)
        .set(ExamSubmission::getIsAccepted, 1)
        .update();

    // 5. 汇总 exam_record.total_score
    int totalScore = lambdaQuery()
        .eq(ExamSubmission::getExamRecordId, submission.getExamRecordId())
        .list()
        .stream().mapToInt(s -> s.getScore() == null ? 0 : s.getScore()).sum();
    examRecordService.lambdaUpdate()
        .eq(ExamRecord::getId, submission.getExamRecordId())
        .set(ExamRecord::getTotalScore, totalScore)
        .set(ExamRecord::getFinishTime, LocalDateTime.now())
        .update();
}
```

---

## 十一、错误码扩展

在现有 `ErrorCode` 枚举中新增（不改变已有枚举项）：

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

## 十二、开发顺序建议

| 阶段 | 任务 | 优先级 |
|------|------|--------|
| 1 | 执行 DDL 建表（4 张新表） | P0 |
| 2 | 创建 4 个实体类 + Mapper 接口（继承 BaseMapper） | P0 |
| 3 | 实现 `ExamService`：创建/更新/删除/列表/详情 | P0 |
| 4 | 实现 `ExamStatusScheduler`，主类加 `@EnableScheduling` | P0 |
| 5 | 实现 `ExamRecordService`：报名 + 考场提交（含 `end_time` 兜底校验） | P0 |
| 6 | 实现 `ExamSubmissionService.onJudgeComplete` + 排行榜 | P1 |
| 7 | 编写 `ExamController` 并通过 Knife4j 联调 | P1 |
| 8 | 扩展 `JudgeServiceImpl` 调用 `onJudgeComplete` | P1 |
| 9 | 编写单元/集成测试（报名流程、提交流程、排行榜计算、定时任务触发） | P2 |
