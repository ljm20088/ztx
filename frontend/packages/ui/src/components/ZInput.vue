<script setup lang="ts">
withDefaults(defineProps<{
  modelValue?: string
  type?: string
  invalid?: boolean
}>(), {
  modelValue: '',
  type: 'text',
  invalid: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()
defineOptions({ inheritAttrs: false })
</script>

<template>
  <input
    v-bind="$attrs"
    class="ztx-input"
    :class="{ 'is-invalid': invalid }"
    :type="type"
    :value="modelValue"
    :aria-invalid="invalid || undefined"
    @input="emit('update:modelValue', ($event.target as HTMLInputElement).value)"
  />
</template>

<style>
.ztx-input {
  width: 100%;
  min-width: 0;
  height: var(--ztx-control-height, 36px);
  padding: 0 10px;
  border: 1px solid var(--ztx-border-strong);
  border-radius: var(--ztx-radius-sm, 4px);
  background: var(--ztx-input-background, #fffefa);
  color: var(--ztx-text);
  font: inherit;
  font-size: var(--ztx-font-size-body, 13px);
  transition: border-color .15s ease, box-shadow .15s ease;
}
.ztx-input:focus {
  outline: none;
  border-color: var(--ztx-primary);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--ztx-primary) 15%, transparent);
}
.ztx-input:disabled {
  opacity: .55;
  cursor: not-allowed;
  background: var(--ztx-surface-soft);
}
.ztx-input.is-invalid,
.ztx-input[aria-invalid="true"] {
  border-color: var(--ztx-danger);
}
</style>
