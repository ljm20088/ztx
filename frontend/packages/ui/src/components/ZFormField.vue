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
  <div class="ztx-form-field">
    <label class="ztx-form-field__label" :for="fieldId">
      <span>{{ label }}</span>
      <span v-if="required" class="ztx-form-field__required" aria-hidden="true">*</span>
    </label>
    <slot />
    <small v-if="hint" class="ztx-form-field__hint">{{ hint }}</small>
  </div>
</template>

<style>
.ztx-form-field {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 6px;
}
.ztx-form-field__label {
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--ztx-text-secondary);
  font-size: var(--ztx-font-size-body, 13px);
  font-weight: 650;
}
.ztx-form-field__required { color: var(--ztx-danger); }
.ztx-form-field__hint {
  color: var(--ztx-muted);
  font-size: var(--ztx-font-size-caption, 12px);
}
</style>
