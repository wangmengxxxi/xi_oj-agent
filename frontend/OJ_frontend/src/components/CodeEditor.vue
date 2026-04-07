<script setup lang="ts">
import { computed } from 'vue'
import { VueMonacoEditor } from '@guolao/vue-monaco-editor'

interface Props {
  modelValue: string
  language?: string
  readOnly?: boolean
  height?: string
}

const props = withDefaults(defineProps<Props>(), {
  language: 'java',
  readOnly: false,
  height: '400px',
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const editorOptions = computed(() => ({
  readOnly: props.readOnly,
  minimap: { enabled: false },
  fontSize: 14,
  lineHeight: 22,
  scrollBeyondLastLine: false,
  automaticLayout: true,
  tabSize: 4,
  scrollbar: {
    verticalScrollbarSize: 10,
    horizontalScrollbarSize: 10,
    useShadows: false,
  },
  overviewRulerBorder: false,
  overviewRulerLanes: 0,
}))

function handleChange(value: string | undefined) {
  if (!props.readOnly) {
    emit('update:modelValue', value ?? '')
  }
}
</script>

<template>
  <VueMonacoEditor
    :value="modelValue"
    :language="language"
    :options="editorOptions"
    :height="height"
    theme="vs"
    @change="handleChange"
  />
</template>
