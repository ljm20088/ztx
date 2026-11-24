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
   <DropdownMenuContent class="ztx-dropdown-menu" :side-offset="6" align="end">
    <DropdownMenuItem v-for="item in items" :key="item.id" class="ztx-dropdown-item" :disabled="item.disabled" @select="emit('select',item.id)">{{item.label}}</DropdownMenuItem>
   </DropdownMenuContent>
  </DropdownMenuPortal>
 </DropdownMenuRoot>
</template>
<style>
.ztx-dropdown-menu{z-index:110;min-width:160px;padding:5px;border:1px solid var(--ztx-border-strong);border-radius:6px;background:var(--ztx-surface);box-shadow:var(--ztx-shadow-popover)}
.ztx-dropdown-item{display:flex;align-items:center;min-height:31px;padding:0 9px;border-radius:4px;color:var(--ztx-text);font-size:11px;outline:none;cursor:pointer}
.ztx-dropdown-item[data-highlighted]{color:var(--ztx-primary-strong);background:var(--ztx-surface-warm)}
.ztx-dropdown-item[data-disabled]{opacity:.4;cursor:not-allowed}
</style>