// ===== 通用 =====
export interface BaseResponse<T = unknown> {
  code: number
  data: T
  message: string
}

export interface Page<T> {
  records: T[]
  total: number
  current: number
  size: number
}

// ===== 用户 =====
export interface LoginUserVO {
  id: number
  userName: string
  userAvatar: string | null
  userProfile: string | null
  userRole: 'user' | 'admin' | 'ban' | 'notLogin'
}

export interface UserVO {
  id: number
  userName: string
  userAvatar: string | null
  userProfile: string | null
  userRole: string
  createTime: string
}

export interface UserLoginRequest {
  userAccount: string
  userPassword: string
}

export interface UserRegisterRequest {
  userAccount: string
  userPassword: string
  checkPassword: string
}

export interface UserUpdateMyRequest {
  userName?: string
  userAvatar?: string
  userProfile?: string
}

// 管理员视角的完整用户信息（来自 /user/list/page 返回 User 实体）
export interface UserAdmin {
  id: number
  userAccount: string
  userName: string | null
  userAvatar: string | null
  userProfile: string | null
  userRole: 'user' | 'admin' | 'ban'
  createTime: string
  updateTime: string
}

export interface UserQueryRequest {
  userName?: string
  userRole?: string
  current?: number
  pageSize?: number
}

export interface UserUpdateAdminRequest {
  id: number
  userName?: string
  userAvatar?: string
  userProfile?: string
  userRole?: string
}

// ===== 题目 =====
export interface JudgeConfig {
  timeLimit: number
  memoryLimit: number
  stackLimit: number
}

export interface JudgeCase {
  input: string
  output: string
}

export interface QuestionVO {
  id: number
  title: string
  content: string
  tags: string[]
  submitNum: number
  acceptedNum: number
  judgeConfig: JudgeConfig
  thumbNum: number
  favourNum: number
  userId: number
  createTime: string
  updateTime: string
  userVO: UserVO
}

export interface Question {
  id: number
  title: string
  content: string
  tags: string[]
  answer: string
  submitNum: number
  acceptedNum: number
  judgeConfig: JudgeConfig
  judgeCase: JudgeCase[]
  thumbNum: number
  favourNum: number
  userId: number
  createTime: string
  updateTime: string
}

export interface QuestionQueryRequest {
  title?: string
  tags?: string[]
  current?: number
  pageSize?: number
  sortField?: string
  sortOrder?: string
}

export interface QuestionAddRequest {
  title: string
  content: string
  tags: string[]
  answer: string
  judgeConfig: JudgeConfig
  judgeCase: JudgeCase[]
}

export interface QuestionUpdateRequest extends QuestionAddRequest {
  id: number
}

export interface QuestionEditRequest extends Partial<QuestionAddRequest> {
  id: number
}

// ===== 题目提交 =====
export interface JudgeInfo {
  message: string | null
  time: number | null
  memory: number | null
}

export interface QuestionSubmitVO {
  id: number
  language: string
  code: string | null
  judgeInfo: JudgeInfo
  status: number // 0待判题 1判题中 2成功 3失败
  questionId: number
  userId: number
  createTime: string
  updateTime: string
  userVO: UserVO | null
  questionVO: QuestionVO | null
}

export interface QuestionSubmitAddRequest {
  language: string
  code: string
  questionId: number
}

export interface QuestionSubmitQueryRequest {
  language?: string
  status?: number
  questionId?: number
  userId?: number
  current?: number
  pageSize?: number
  sortField?: string
  sortOrder?: string
}
