<script setup lang="ts">
withDefaults(defineProps<{
  tone?: 'info' | 'success' | 'error'
  role?: 'status' | 'alert'
}>(), {
  tone: 'info',
  role: 'status',
})
</script>

<template>
  <div
    class="ztx-alert rounded-ztx-sm border px-2.5 py-2 text-ztx-caption leading-relaxed"
    :class="{
      'is-info border-ztx-border bg-ztx-surface-soft text-ztx-text-secondary': tone === 'info',
      'is-success border-ztx-border bg-ztx-surface-soft text-ztx-success': tone === 'success',
      'is-error border-ztx-danger bg-ztx-surface-warm text-ztx-danger': tone === 'error',
    }"
    :role="role"
  ><slot /></div>
</template>
