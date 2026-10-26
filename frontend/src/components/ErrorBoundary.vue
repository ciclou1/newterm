<script setup lang="ts">
import { onErrorCaptured, ref } from 'vue'

/**
 * Last line of defence for a single view. A render/runtime failure must not
 * blank the whole application, and the user gets a retry instead of a stack
 * trace.
 */
const failed = ref(false)
const resetKey = ref(0)

onErrorCaptured((error, _instance, info) => {
  failed.value = true
  // Keep diagnostics in the developer console only; the UI never prints them.
  console.error('View render failed', info, error)
  return false
})

function retry() {
  failed.value = false
  resetKey.value += 1
}
</script>

<template>
  <div v-if="failed" class="empty-state" role="alert">
    <p style="margin: 0 0 12px">此模块暂时无法显示。</p>
    <button class="btn" type="button" @click="retry">重新加载模块</button>
  </div>
  <slot v-else :key="resetKey" />
</template>
