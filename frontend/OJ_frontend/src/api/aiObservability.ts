import request from './request'
import type { BaseResponse } from '@/types'

export interface AiModuleCallMetric {
  module: string
  count: number
}

export interface AiToolMetric {
  toolName: string
  callCount: number
  failedCount: number
}

export interface AiMetricsSnapshot {
  date: string
  todayAiCalls: number
  todayRateLimited: number
  avgDurationMs: number
  maxDurationMs: number
  avgAgentSteps: number
  toolFailedCount: number
  ragEmptyCount: number
  rerankCallCount: number
  rerankFailedCount: number
  linkRemovedCount: number
  moduleDistribution: AiModuleCallMetric[]
}

export interface AiObservationEvent {
  id: number
  eventType: string
  module: string | null
  userId: number | null
  chatId: string | null
  targetKey: string | null
  success: number
  durationMs: number
  countValue: number
  detail: string | null
  createTime: string
}

export interface AgentTraceLog {
  id: number
  userId: number | null
  chatId: string | null
  query: string | null
  stepIndex: number | null
  thought: string | null
  toolName: string | null
  toolSuccess: number | null
  retryCount: number | null
  durationMs: number | null
  createTime: string
}

export const getAiObservabilitySummary = (date?: string) =>
  request.get<BaseResponse<AiMetricsSnapshot>>('/admin/ai/observability/summary', {
    params: { date },
  })

export const getAiToolTop = (date?: string, limit = 10) =>
  request.get<BaseResponse<AiToolMetric[]>>('/admin/ai/observability/tool-top', {
    params: { date, limit },
  })

export const getAiRecentEvents = (limit = 50) =>
  request.get<BaseResponse<AiObservationEvent[]>>('/admin/ai/observability/recent-events', {
    params: { limit },
  })

export const getAiAgentTrace = (date?: string, limit = 50) =>
  request.get<BaseResponse<AgentTraceLog[]>>('/admin/ai/observability/agent-trace', {
    params: { date, limit },
  })
