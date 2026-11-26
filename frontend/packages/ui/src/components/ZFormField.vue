<script setup lang="ts">
withDefaults(defineProps<{
  label: string
  fieldId?: string
  required?: boolean
  hint?: string
}>(), {
  required: false,
})
</script>

<template>
  <div class="ztx-form-field flex min-w-0 flex-col gap-1.5">
    <label class="ztx-form-field__label flex items-center gap-1 text-ztx-body font-semibold text-ztx-text-secondary" :for="fieldId">
      <span>{{ label }}</span>
      <span v-if="required" class="text-ztx-danger" aria-hidden="true">*</span>
    </label>
    <slot />
    <small v-if="hint" class="ztx-form-field__hint text-ztx-caption text-ztx-muted">{{ hint }}</small>
  </div>
</template>
