import request from './request'
import type { BaseResponse } from '@/types'

export const uploadFile = (file: File, biz: string) => {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('biz', biz)
  return request.post<BaseResponse<string>>('/file/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}
