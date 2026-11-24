<script setup lang="ts">
defineProps<{tabs:Array<{id:string;label:string;active?:boolean}>}>()
defineEmits<{select:[id:string]}>()
</script>
<template><div class="ztx-tabs" role="tablist"><button v-for="tab in tabs" :key="tab.id" class="ztx-tab" :class="{'is-active':tab.active}" role="tab" :aria-selected="tab.active" @click="$emit('select',tab.id)">{{tab.label}}</button></div></template>
<style>
.ztx-tabs{display:flex;align-items:center;gap:2px;border-bottom:1px solid var(--ztx-border)}.ztx-tab{min-height:34px;padding:0 12px;border:0;border-bottom:2px solid transparent;color:var(--ztx-muted);background:transparent;font-size:11px;cursor:pointer}.ztx-tab.is-active{color:var(--ztx-primary-strong);border-bottom-color:var(--ztx-primary);font-weight:650}
</style>