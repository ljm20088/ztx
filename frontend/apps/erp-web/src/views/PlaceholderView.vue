<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ZArrowLeft, ZConstruction, ZButton } from '@ztx/ui'
import { findFeature } from '../navigation/menu'
const route=useRoute(),router=useRouter()
const feature=computed(()=>findFeature(route.path))
const title=computed(()=>feature.value?.label??'功能页面')
const description=computed(()=>feature.value?.description??'该入口用于验证工作区导航和多标签行为。')
</script>
<template><section class="placeholder-page"><div class="placeholder-card">
 <span class="placeholder-icon"><ZConstruction :size="24"/></span><span class="eyebrow">PLATFORM FOUNDATION / PREVIEW</span>
 <h1>{{title}}</h1><p>{{description}}</p><div class="placeholder-divider"></div>
 <div class="placeholder-note"><strong>功能入口已接入工作区</strong><span>当前 Issue 只交付平台工作区和基础导航。此处暂不实现具体业务功能。</span></div>
 <ZButton variant="secondary" @click="router.push('/workspace')"><ZArrowLeft :size="14"/> 返回工作台</ZButton>
</div></section></template>