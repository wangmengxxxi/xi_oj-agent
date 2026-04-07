<script setup lang="ts">
import { MdPreview } from 'md-editor-v3'
import 'md-editor-v3/lib/preview.css'

interface Props {
  content: string
}

defineProps<Props>()
</script>

<template>
  <MdPreview
    editor-id="md-viewer"
    :model-value="content"
    :show-code-row-number="true"
    preview-theme="default"
  />
</template>

<style scoped>
:deep(.md-editor-preview-wrapper) {
  padding: 0;
}
</style>
