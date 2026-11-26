<script setup lang="ts">
withDefaults(defineProps<{variant?:'primary'|'secondary'|'ghost'|'danger';size?:'sm'|'md'|'lg';disabled?:boolean;type?:'button'|'submit'|'reset'}>(),{variant:'primary',size:'md',disabled:false,type:'button'})
</script>
<template>
  <button
    class="ztx-button inline-flex min-h-ztx-control items-center justify-center gap-2 rounded-ztx-sm border border-transparent px-3 text-ztx-body font-semibold transition-colors disabled:cursor-not-allowed disabled:opacity-45"
    :class="[
      'is-' + variant,
      'size-' + size,
      size === 'sm' ? 'min-h-[29px] px-[9px]' : '',
      size === 'md' ? 'min-h-[34px] px-[13px]' : '',
      size === 'lg' ? 'min-h-[40px] px-4' : '',
      variant === 'primary' ? 'bg-ztx-primary text-white hover:bg-ztx-primary-hover' : '',
      variant === 'secondary' ? 'border-ztx-border-strong bg-ztx-surface text-ztx-text hover:bg-ztx-surface-warm' : '',
      variant === 'ghost' ? 'bg-transparent text-ztx-text-secondary hover:bg-ztx-surface-soft' : '',
      variant === 'danger' ? 'bg-ztx-danger text-white' : '',
    ]"
    :type="type"
    :disabled="disabled"
  ><slot /></button>
</template>
