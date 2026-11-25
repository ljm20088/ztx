<script setup lang="ts">
withDefaults(defineProps<{variant?:'primary'|'secondary'|'ghost'|'danger';size?:'sm'|'md'|'lg';disabled?:boolean;type?:'button'|'submit'|'reset'}>(),{variant:'primary',size:'md',disabled:false,type:'button'})
</script>
<template><button class="ztx-button" :class="['is-'+variant,'size-'+size]" :type="type" :disabled="disabled"><slot /></button></template>
<style>
.ztx-button{min-height:var(--ztx-control-height,32px);display:inline-flex;align-items:center;justify-content:center;gap:7px;padding:0 11px;border:1px solid transparent;border-radius:var(--ztx-radius-sm,4px);font-size:var(--ztx-font-size-body,13px);font-weight:650;cursor:pointer;transition:background .15s ease,border-color .15s ease}
.ztx-button:disabled{opacity:.45;cursor:not-allowed}.ztx-button.size-sm{min-height:29px;padding:0 9px}.ztx-button.size-md{min-height:34px;padding:0 13px}.ztx-button.size-lg{min-height:40px;padding:0 16px}
.ztx-button.is-primary{color:#fffaf5;background:var(--ztx-primary)}.ztx-button.is-primary:hover:not(:disabled){background:var(--ztx-primary-hover)}
.ztx-button.is-secondary{color:var(--ztx-text);background:var(--ztx-surface);border-color:var(--ztx-border-strong)}.ztx-button.is-secondary:hover:not(:disabled){background:var(--ztx-surface-warm)}
.ztx-button.is-ghost{color:var(--ztx-text-secondary);background:transparent}.ztx-button.is-ghost:hover:not(:disabled){background:var(--ztx-surface-soft)}
.ztx-button.is-danger{color:#fffaf5;background:var(--ztx-danger)}
</style>