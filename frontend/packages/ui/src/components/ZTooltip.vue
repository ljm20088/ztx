<script setup lang="ts">
import { TooltipArrow, TooltipContent, TooltipPortal, TooltipProvider, TooltipRoot, TooltipTrigger } from 'reka-ui'
defineProps<{text:string}>()
</script>
<template>
  <TooltipProvider :delay-duration="350">
    <TooltipRoot><TooltipTrigger as-child><slot /></TooltipTrigger>
      <TooltipPortal><TooltipContent class="ztx-tooltip" :side-offset="6">{{ text }}<TooltipArrow class="ztx-tooltip-arrow" /></TooltipContent></TooltipPortal>
    </TooltipRoot>
  </TooltipProvider>
</template>
<style>
.ztx-tooltip{z-index:100;padding:5px 8px;border:1px solid var(--ztx-border-strong);border-radius:4px;color:var(--ztx-surface);background:#39332d;font-size:10px;box-shadow:0 3px 10px rgba(0,0,0,.12)}
.ztx-tooltip-arrow{fill:#39332d}
</style>