<script setup lang="ts"></script>

<template>
  <div class="ztx-table-wrap">
    <table class="ztx-table"><slot /></table>
  </div>
</template>

<style>
.ztx-table-wrap { width: 100%; overflow-x: auto; }
.ztx-table { width: 100%; border-collapse: collapse; font-size: var(--ztx-font-size-body, 13px); }
.ztx-table th,
.ztx-table td { padding: 9px 8px; border-bottom: 1px solid var(--ztx-border); text-align: left; }
.ztx-table th { color: var(--ztx-muted); font-weight: 600; white-space: nowrap; }
.ztx-table td code { font-size: var(--ztx-font-size-caption, 12px); }
@media (max-width: 760px) {
  .ztx-table-wrap { display: block; }
}
</style>
