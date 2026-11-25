<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ZArrowLeft, ZConstruction, ZButton } from '@ztx/ui'
const router=useRouter()
</script>
<template>
  <section class="placeholder-page">
    <div class="placeholder-card">
      <span class="placeholder-icon"><ZConstruction :size="24"/></span>
      <p class="placeholder-state">该功能尚未开放</p>
      <ZButton variant="secondary" @click="router.push('/workspace')">
        <ZArrowLeft :size="14"/> 返回工作台
      </ZButton>
    </div>
  </section>
</template>
