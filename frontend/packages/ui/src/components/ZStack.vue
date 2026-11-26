<script setup lang="ts">
withDefaults(defineProps<{as?:'div'|'section'|'nav';direction?:'row'|'column';gap?:string;align?:string;justify?:string;wrap?:boolean}>(),{as:'div',direction:'row',gap:'8px',align:'center',justify:'flex-start',wrap:false})
</script>
<template><component :is="as" class="ztx-stack flex min-w-0" :style="{flexDirection:direction,gap,alignItems:align,justifyContent:justify,flexWrap:wrap?'wrap':'nowrap'}"><slot/></component></template>
