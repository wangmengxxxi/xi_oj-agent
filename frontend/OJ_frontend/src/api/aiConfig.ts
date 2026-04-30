import request from './request'
import type { BaseResponse, AiConfigUpdateRequest } from '@/types'

export const getAiConfig = () =>
  request.get<BaseResponse<Record<string, string>>>('/admin/ai/config')

export const updateAiConfig = (data: AiConfigUpdateRequest) =>
  request.post<BaseResponse<string>>('/admin/ai/config', data)

export const testProviderConnection = (data: {
  apiKey: string
  baseUrl: string
  modelName: string
}) => request.post<BaseResponse<string>>('/admin/ai/provider/test', data)

export const rebuildQuestionVectors = () =>
  request.post<BaseResponse<string>>('/admin/ai/question-vector/rebuild')

export const importKnowledge = (file: File) => {
  const formData = new FormData()
  formData.append('file', file)
  return request.post<BaseResponse<string>>('/admin/knowledge/import', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 300000,
  })
}

export const getImportStatus = (taskId: string) =>
  request.get<BaseResponse<{
    status: string
    filename: string
    message: string
    progress: number
    currentStep: string
  }>>(`/admin/knowledge/import/status/${taskId}`)
