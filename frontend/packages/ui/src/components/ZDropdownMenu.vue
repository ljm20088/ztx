<script setup lang="ts">
import { DropdownMenuContent, DropdownMenuItem, DropdownMenuPortal, DropdownMenuRoot, DropdownMenuTrigger } from 'reka-ui'
interface MenuItem { id:string; label:string; disabled?:boolean }
defineProps<{items:MenuItem[]}>()
const emit=defineEmits<{select:[id:string]}>()
</script>
<template>
 <DropdownMenuRoot>
  <DropdownMenuTrigger as-child><slot name="trigger"/></DropdownMenuTrigger>
  <DropdownMenuPortal>
   <DropdownMenuContent class="z-[110] min-w-40 rounded-[6px] border border-ztx-border-strong bg-ztx-surface p-[5px] shadow-[var(--ztx-shadow-popover)]" :side-offset="6" align="end">
    <DropdownMenuItem v-for="item in items" :key="item.id" class="flex min-h-[31px] cursor-pointer items-center rounded px-[9px] text-ztx-body text-ztx-text outline-none data-[highlighted]:bg-ztx-surface-warm data-[highlighted]:text-ztx-primary-strong data-[disabled]:cursor-not-allowed data-[disabled]:opacity-40" :disabled="item.disabled" @select="emit('select',item.id)">{{item.label}}</DropdownMenuItem>
   </DropdownMenuContent>
  </DropdownMenuPortal>
 </DropdownMenuRoot>
</template>
