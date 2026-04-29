export interface SSECallbacks {
  onToken: (token: string) => void
  onDone: () => void
  onError: (msg: string) => void
  onStatus?: (msg: string) => void
}

export function fetchSSE(
  url: string,
  body: Record<string, unknown>,
  callbacks: SSECallbacks,
): AbortController {
  const controller = new AbortController()

  fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    signal: controller.signal,
    body: JSON.stringify(body),
  })
    .then(async (response) => {
      if (!response.ok || !response.body) {
        callbacks.onError(`请求失败: ${response.status}`)
        return
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let currentEventType = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() ?? ''

        for (const line of lines) {
          if (line.startsWith('event:')) {
            currentEventType = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            const raw = line.slice(5).trim()
            if (!raw) continue
            try {
              const json = JSON.parse(raw)
              if (currentEventType === 'error') {
                callbacks.onError(json.error ?? '流式输出异常')
              } else if (currentEventType === 'status') {
                callbacks.onStatus?.(json.d ?? '')
              } else if (json.done === true) {
                callbacks.onDone()
              } else if ('d' in json) {
                callbacks.onToken(json.d ?? '')
              }
            } catch {
              callbacks.onToken(raw)
            }
            currentEventType = ''
          }
        }
      }
    })
    .catch((err) => {
      if (err?.name !== 'AbortError') {
        callbacks.onError(err?.message ?? '网络异常')
      }
    })

  return controller
}
