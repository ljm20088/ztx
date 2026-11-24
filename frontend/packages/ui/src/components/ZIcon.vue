<script setup lang="ts">
import type { Component } from 'vue'
withDefaults(defineProps<{icon:Component;size?:number;label?:string}>(),{size:16,label:undefined})
</script>
<template><component :is="icon" :size="size" :stroke-width="1.8" :aria-label="label" :aria-hidden="label?undefined:'true'" /></template>