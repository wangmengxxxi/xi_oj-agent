import request from './request'
import type {
  QuestionVO,
  Question,
  QuestionQueryRequest,
  QuestionAddRequest,
  QuestionUpdateRequest,
  QuestionEditRequest,
  BaseResponse,
  Page,
} from '@/types'

export const getQuestionVOById = (id: number) =>
  request.get<BaseResponse<QuestionVO>>('/question/get/vo', { params: { id } })

export const getQuestionById = (id: number) =>
  request.get<BaseResponse<Question>>('/question/get', { params: { id } })

export const listQuestionVOByPage = (data: QuestionQueryRequest) =>
  request.post<BaseResponse<Page<QuestionVO>>>('/question/list/page/vo', data)

export const listQuestionByPage = (data: QuestionQueryRequest) =>
  request.post<BaseResponse<Page<Question>>>('/question/list/page', data)

export const addQuestion = (data: QuestionAddRequest) =>
  request.post<BaseResponse<number>>('/question/add', data)

export const updateQuestion = (data: QuestionUpdateRequest) =>
  request.post<BaseResponse<boolean>>('/question/update', data)

export const editQuestion = (data: QuestionEditRequest) =>
  request.post<BaseResponse<boolean>>('/question/edit', data)

export const deleteQuestion = (id: number) =>
  request.post<BaseResponse<boolean>>('/question/delete', { id })
