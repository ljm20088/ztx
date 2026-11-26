<script setup lang="ts">
import { TooltipArrow, TooltipContent, TooltipPortal, TooltipProvider, TooltipRoot, TooltipTrigger } from 'reka-ui'
defineProps<{text:string}>()
</script>
<template>
  <TooltipProvider :delay-duration="350">
    <TooltipRoot><TooltipTrigger as-child><slot /></TooltipTrigger>
      <TooltipPortal><TooltipContent class="z-[100] rounded-[4px] border border-ztx-border-strong bg-ztx-tooltip px-2 py-[5px] text-ztx-caption text-white shadow-ztx-tooltip" :side-offset="6">{{ text }}<TooltipArrow class="fill-ztx-tooltip" /></TooltipContent></TooltipPortal>
    </TooltipRoot>
  </TooltipProvider>
</template>
