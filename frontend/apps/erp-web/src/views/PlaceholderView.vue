<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ZArrowLeft, ZConstruction, ZButton } from '@ztx/ui'
const router=useRouter()
</script>
<template>
  <section class="grid min-h-[500px] flex-1 place-items-center">
    <div class="w-full max-w-[480px] rounded-ztx-md border border-ztx-border bg-ztx-surface p-[30px] text-center">
      <span class="mx-auto mb-4 grid size-[50px] place-items-center rounded-[10px] bg-ztx-surface-warm text-ztx-primary"><ZConstruction :size="24"/></span>
      <p data-testid="placeholder-state" class="mb-5 text-ztx-caption text-ztx-muted">该功能尚未开放</p>
      <ZButton variant="secondary" @click="router.push('/workspace')"><ZArrowLeft :size="14"/> 返回工作台</ZButton>
    </div>
  </section>
</template>
