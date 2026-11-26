<script setup lang="ts">
import ZTooltip from './ZTooltip.vue'
withDefaults(defineProps<{
  label: string
  disabled?: boolean
  buttonClass?: string
  hasPopup?: 'menu' | 'listbox' | 'tree' | 'grid' | 'dialog' | 'true' | 'false'
  expanded?: boolean
}>(), {
  disabled: false,
  expanded: false,
})
defineEmits<{ click: [event: MouseEvent] }>()
</script>
<template>
  <ZTooltip :text="label">
    <button
      :class="['ztx-icon-button inline-grid h-[30px] w-[30px] place-items-center rounded-ztx-sm border-0 bg-transparent text-ztx-text-secondary hover:bg-ztx-surface-warm hover:text-ztx-primary-strong disabled:cursor-not-allowed disabled:opacity-40', buttonClass]"
      type="button"
      :aria-label="label"
      :aria-haspopup="hasPopup"
      :aria-expanded="hasPopup ? expanded : undefined"
      :disabled="disabled"
      @click="$emit('click', $event)"
    >
      <slot />
    </button>
  </ZTooltip>
</template>
