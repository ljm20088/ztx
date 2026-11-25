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
  <div class="ztx-alert" :class="'is-' + tone" :role="role">
    <slot />
  </div>
</template>

<style>
.ztx-alert {
  padding: 9px 10px;
  border: 1px solid transparent;
  border-radius: var(--ztx-radius-sm, 4px);
  font-size: var(--ztx-font-size-caption, 12px);
  line-height: 1.6;
}
.ztx-alert.is-info {
  color: var(--ztx-text-secondary);
  background: var(--ztx-surface-soft);
  border-color: var(--ztx-border);
}
.ztx-alert.is-success {
  color: var(--ztx-success, #386b4d);
  background: var(--ztx-success-soft, #edf5ef);
}
.ztx-alert.is-error {
  color: var(--ztx-danger, #934b3e);
  background: var(--ztx-danger-soft, #f8e9e5);
}
</style>
