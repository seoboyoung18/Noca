<script setup>
defineProps({ on: Boolean })
</script>

<template>
  <span class="chk" :class="{ on }">
    <svg width="13" height="13" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M3 8.6L6.4 12L13 4.6" stroke="#FFFFFF" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
  </span>
</template>
