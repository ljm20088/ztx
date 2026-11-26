<script setup lang="ts">
import { TooltipArrow, TooltipContent, TooltipPortal, TooltipProvider, TooltipRoot, TooltipTrigger } from 'reka-ui'
defineProps<{text:string}>()
</script>
<template>
  <TooltipProvider :delay-duration="350">
    <TooltipRoot><TooltipTrigger as-child><slot /></TooltipTrigger>
      <TooltipPortal><TooltipContent class="z-[100] rounded-[4px] border border-ztx-border-strong bg-[#39332d] px-2 py-[5px] text-ztx-caption text-white shadow-[0_3px_10px_rgba(0,0,0,0.12)]" :side-offset="6">{{ text }}<TooltipArrow class="fill-[#39332d]" /></TooltipContent></TooltipPortal>
    </TooltipRoot>
  </TooltipProvider>
</template>
