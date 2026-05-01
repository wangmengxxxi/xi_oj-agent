import request from './request'
import type {
  BaseResponse,
  AiChatRequest,
  AiChatClearRequest,
  AiChatRecord,
  AiChatHistoryPageRequest,
  AiChatHistoryPageResponse,
  AiCodeAnalysisRequest,
  AiCodeAnalysis,
  AiQuestionParseRequest,
  AiQuestionParseResponse,
  WrongQuestionVO,
  WrongQuestionReviewRequest,
  ApiId,
} from '@/types'

// ===== AI Chat =====
export const aiChat = (data: AiChatRequest) =>
  request.post<BaseResponse<string>>('/ai/chat', data)

export const getAiChatHistory = (chatId: string) =>
  request.get<BaseResponse<AiChatRecord[]>>('/ai/chat/history', { params: { chatId } })

export const getAiChatHistoryPage = (data: AiChatHistoryPageRequest) =>
  request.post<BaseResponse<AiChatHistoryPageResponse>>('/ai/chat/history/page', data)

export const clearAiChat = (data: AiChatClearRequest) =>
  request.post<BaseResponse<string>>('/ai/chat/clear', data)

export const getAiChatSessions = () =>
  request.get<BaseResponse<{ chatId: string; label: string; lastTime: string }[]>>('/ai/chat/sessions')

// ===== AI Code Analysis =====
export const aiCodeAnalysis = (data: AiCodeAnalysisRequest) =>
  request.post<BaseResponse<string>>('/ai/code/analysis', data)

export const getAiCodeHistory = (questionId?: number | string, pageSize?: number) =>
  request.get<BaseResponse<AiCodeAnalysis[]>>('/ai/code/history', {
    params: { questionId, pageSize },
  })

// ===== AI Question Parse =====
export const aiQuestionParse = (data: AiQuestionParseRequest) =>
  request.post<BaseResponse<AiQuestionParseResponse>>('/ai/question/parse', data)

export const getAiSimilarQuestions = (questionId: ApiId) =>
  request.get<BaseResponse<ApiId[]>>('/ai/question/similar', { params: { questionId } })

// ===== AI Wrong Question =====
export const getWrongQuestionList = () =>
  request.get<BaseResponse<WrongQuestionVO[]>>('/ai/wrong-question/list')

export const getDueWrongQuestionList = () =>
  request.get<BaseResponse<WrongQuestionVO[]>>('/ai/wrong-question/due')

export const getWrongQuestionAnalysis = (wrongQuestionId: ApiId) =>
  request.get<BaseResponse<string>>('/ai/wrong-question/analysis', {
    params: { wrongQuestionId },
  })

export const reviewWrongQuestion = (data: WrongQuestionReviewRequest) =>
  request.post<BaseResponse<string>>('/ai/wrong-question/review', data)
