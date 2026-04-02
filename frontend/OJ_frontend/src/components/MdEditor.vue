<script setup lang="ts">
import { MdEditor } from 'md-editor-v3'
import 'md-editor-v3/lib/style.css'

interface Props {
  modelValue: string
  placeholder?: string
  height?: string
}

withDefaults(defineProps<Props>(), {
  placeholder: '请输入内容...',
  height: '400px',
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()
</script>

<template>
  <MdEditor
    :model-value="modelValue"
    :placeholder="placeholder"
    :style="{ height }"
    @update:model-value="(v) => emit('update:modelValue', v)"
  />
</template>
