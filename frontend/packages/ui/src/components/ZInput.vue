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
    class="ztx-input h-ztx-control w-full min-w-0 rounded-ztx-sm border bg-ztx-surface px-2.5 text-ztx-body text-ztx-text transition-colors placeholder:text-ztx-muted focus:border-ztx-primary focus:outline-none focus:ring-2 focus:ring-ztx-primary/15 disabled:cursor-not-allowed disabled:bg-ztx-surface-soft disabled:opacity-55"
    :class="invalid ? 'border-ztx-danger' : 'border-ztx-border-strong'"
    :type="type"
    :value="modelValue"
    :aria-invalid="invalid || undefined"
    @input="emit('update:modelValue', ($event.target as HTMLInputElement).value)"
  />
</template>
