<script setup lang="ts">
import ZTooltip from './ZTooltip.vue'
defineProps<{label:string;disabled?:boolean}>()
defineEmits<{click:[event:MouseEvent]}>()
</script>
<template><ZTooltip :text="label"><button class="ztx-icon-button" type="button" :aria-label="label" :disabled="disabled" @click="$emit('click',$event)"><slot /></button></ZTooltip></template>
<style>
.ztx-icon-button{width:30px;height:30px;display:grid;place-items:center;border:0;border-radius:5px;color:var(--ztx-text-secondary);background:transparent;cursor:pointer}
.ztx-icon-button:hover:not(:disabled){color:var(--ztx-primary-strong);background:var(--ztx-surface-warm)}.ztx-icon-button:disabled{opacity:.4;cursor:not-allowed}
</style>