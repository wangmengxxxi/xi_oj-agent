<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import {
  getAiAgentTrace,
  getAiObservabilitySummary,
  getAiRecentEvents,
  getAiToolTop,
  type AgentTraceLog,
  type AiMetricsSnapshot,
  type AiObservationEvent,
  type AiToolMetric,
} from '@/api/aiObservability'

const today = new Date().toISOString().slice(0, 10)
const date = ref(today)
const loading = ref(false)
const summary = ref<AiMetricsSnapshot | null>(null)
const toolTop = ref<AiToolMetric[]>([])
const recentEvents = ref<AiObservationEvent[]>([])
const agentTrace = ref<AgentTraceLog[]>([])

const moduleTotal = computed(() =>
  (summary.value?.moduleDistribution || []).reduce((sum, item) => sum + Number(item.count || 0), 0)
)

const statItems = computed(() => [
  { label: 'AI calls', value: summary.value?.todayAiCalls ?? 0 },
  { label: 'Rate limited', value: summary.value?.todayRateLimited ?? 0 },
  { label: 'Avg latency', value: formatMs(summary.value?.avgDurationMs ?? 0) },
  { label: 'Max latency', value: formatMs(summary.value?.maxDurationMs ?? 0) },
  { label: 'RAG empty', value: summary.value?.ragEmptyCount ?? 0 },
  { label: 'Links removed', value: summary.value?.linkRemovedCount ?? 0 },
  { label: 'Rerank calls', value: summary.value?.rerankCallCount ?? 0 },
  { label: 'Rerank failed', value: summary.value?.rerankFailedCount ?? 0 },
])

const eventColumns = [
  { title: 'Time', dataIndex: 'createTime', width: 170 },
  { title: 'Type', dataIndex: 'eventType', width: 150 },
  { title: 'Module', dataIndex: 'module', width: 130 },
  { title: 'Target', dataIndex: 'targetKey', width: 160 },
  { title: 'Detail', dataIndex: 'detail', ellipsis: true, tooltip: true },
]

const traceColumns = [
  { title: 'Time', dataIndex: 'createTime', width: 170 },
  { title: 'Chat', dataIndex: 'chatId', width: 150 },
  { title: 'Step', dataIndex: 'stepIndex', width: 80 },
  { title: 'Tool', dataIndex: 'toolName', width: 160 },
  { title: 'OK', slotName: 'toolSuccess', width: 90 },
  { title: 'Latency', slotName: 'durationMs', width: 110 },
]

async function loadDashboard() {
  loading.value = true
  try {
    const [summaryRes, toolRes, eventRes, traceRes] = await Promise.all([
      getAiObservabilitySummary(date.value),
      getAiToolTop(date.value, 10),
      getAiRecentEvents(50),
      getAiAgentTrace(date.value, 50),
    ])
    summary.value = summaryRes.data.data
    toolTop.value = toolRes.data.data || []
    recentEvents.value = eventRes.data.data || []
    agentTrace.value = traceRes.data.data || []
  } catch (err: any) {
    Message.error(err?.message || 'Failed to load AI observability data')
  } finally {
    loading.value = false
  }
}

function modulePercent(count: number) {
  if (moduleTotal.value <= 0) return 0
  return Math.round((Number(count || 0) / moduleTotal.value) * 100)
}

function formatMs(value: number) {
  if (!value) return '0 ms'
  if (value >= 1000) return `${(value / 1000).toFixed(2)} s`
  return `${value} ms`
}

onMounted(loadDashboard)
</script>

<template>
  <div class="ai-observability">
    <div class="page-header">
      <div>
        <h1>AI Observability</h1>
        <p>Business metrics for AI calls, rate limits, RAG, rerank, tools, and traces.</p>
      </div>
      <a-space>
        <input v-model="date" class="date-input" type="date" />
        <a-button type="primary" :loading="loading" @click="loadDashboard">Refresh</a-button>
      </a-space>
    </div>

    <a-spin :loading="loading" class="dashboard-spin">
      <div class="stat-grid">
        <div v-for="item in statItems" :key="item.label" class="stat-card">
          <div class="stat-label">{{ item.label }}</div>
          <div class="stat-value">{{ item.value }}</div>
        </div>
      </div>

      <div class="panel-grid">
        <section class="panel">
          <div class="panel-title">Module Distribution</div>
          <div class="metric-list">
            <div
              v-for="item in summary?.moduleDistribution || []"
              :key="item.module"
              class="metric-row"
            >
              <div class="metric-head">
                <span>{{ item.module }}</span>
                <strong>{{ item.count }}</strong>
              </div>
              <a-progress :percent="modulePercent(item.count) / 100" size="small" />
            </div>
          </div>
        </section>

        <section class="panel">
          <div class="panel-title">Tool TopN</div>
          <div class="tool-list">
            <div v-for="tool in toolTop" :key="tool.toolName" class="tool-row">
              <div>
                <div class="tool-name">{{ tool.toolName }}</div>
                <div class="tool-sub">failed {{ tool.failedCount || 0 }}</div>
              </div>
              <strong>{{ tool.callCount }}</strong>
            </div>
            <a-empty v-if="toolTop.length === 0" />
          </div>
        </section>
      </div>

      <section class="panel">
        <div class="panel-title">Recent Events</div>
        <a-table
          :columns="eventColumns"
          :data="recentEvents"
          :pagination="{ pageSize: 10 }"
          row-key="id"
        />
      </section>

      <section class="panel">
        <div class="panel-title">Recent Agent Trace</div>
        <a-table
          :columns="traceColumns"
          :data="agentTrace"
          :pagination="{ pageSize: 10 }"
          row-key="id"
        >
          <template #toolSuccess="{ record }">
            <a-tag v-if="record.toolName" :color="record.toolSuccess === 0 ? 'red' : 'green'">
              {{ record.toolSuccess === 0 ? 'failed' : 'ok' }}
            </a-tag>
            <span v-else>-</span>
          </template>
          <template #durationMs="{ record }">
            {{ formatMs(record.durationMs || 0) }}
          </template>
        </a-table>
      </section>
    </a-spin>
  </div>
</template>

<style scoped>
.ai-observability {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.page-header h1 {
  margin: 0;
  font-size: 22px;
  color: #1d2129;
}

.page-header p {
  margin: 6px 0 0;
  color: #6b7785;
  font-size: 13px;
}

.date-input {
  height: 32px;
  padding: 0 10px;
  border: 1px solid #c9cdd4;
  border-radius: 6px;
  color: #1d2129;
  background: #fff;
}

.dashboard-spin {
  width: 100%;
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
}

.stat-card {
  min-height: 92px;
  padding: 16px;
  border-radius: 8px;
  border: 1px solid #e5e6eb;
  background: #fff;
}

.stat-label {
  color: #6b7785;
  font-size: 13px;
}

.stat-value {
  margin-top: 12px;
  color: #1d2129;
  font-size: 24px;
  font-weight: 700;
  line-height: 1.1;
}

.panel-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
}

.panel {
  margin-top: 16px;
  padding: 16px;
  border-radius: 8px;
  border: 1px solid #e5e6eb;
  background: #fff;
}

.panel-title {
  margin-bottom: 14px;
  color: #1d2129;
  font-size: 16px;
  font-weight: 600;
}

.metric-list,
.tool-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.metric-head,
.tool-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.tool-row {
  min-height: 48px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f2f3f5;
}

.tool-row:last-child {
  border-bottom: 0;
  padding-bottom: 0;
}

.tool-name {
  color: #1d2129;
  font-weight: 600;
}

.tool-sub {
  margin-top: 2px;
  color: #86909c;
  font-size: 12px;
}

@media (max-width: 768px) {
  .page-header,
  .panel-grid {
    grid-template-columns: 1fr;
    flex-direction: column;
  }

  .page-header {
    align-items: stretch;
  }
}
</style>
