import request from './request'
import type {
  QuestionSubmitVO,
  QuestionSubmitAddRequest,
  QuestionSubmitQueryRequest,
  BaseResponse,
  Page,
} from '@/types'

export const doQuestionSubmit = (data: QuestionSubmitAddRequest) =>
  request.post<BaseResponse<number>>('/question_submit/', data)

export const listQuestionSubmitByPage = (data: QuestionSubmitQueryRequest) =>
  request.post<BaseResponse<Page<QuestionSubmitVO>>>('/question_submit/list/page', data)
