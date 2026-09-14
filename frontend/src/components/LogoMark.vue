<script setup>
defineProps({
  size: { type: Number, default: 32 },
  radius: { type: Number, default: 10 },
  icon: { type: Number, default: 18 },
})
</script>

<template>
  <span class="logomark" :style="{ width: size + 'px', height: size + 'px', borderRadius: radius + 'px' }">
    <svg :width="icon" :height="icon" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M2.5 18V10.25A6.5 6.5 0 0 1 15.5 10.25V14H20A2 2 0 0 1 20 18Z" fill="#FFFFFF"/></svg>
  </span>
</template>

<style scoped>
.logomark { flex: 0 0 auto; background: var(--primary); display: flex; align-items: center; justify-content: center; }
</style>
