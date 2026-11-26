<script setup lang="ts">
defineProps<{tabs:Array<{id:string;label:string;active?:boolean}>}>()
defineEmits<{select:[id:string]}>()
</script>
<template><div class="flex items-center gap-0.5 border-b border-ztx-border" role="tablist"><button v-for="tab in tabs" :key="tab.id" class="min-h-[34px] border-0 border-b-2 border-transparent bg-transparent px-3 text-ztx-body text-ztx-muted hover:text-ztx-text" :class="{'!border-ztx-primary !font-semibold !text-ztx-primary-strong':tab.active}" role="tab" :aria-selected="tab.active" @click="$emit('select',tab.id)">{{tab.label}}</button></div></template>
