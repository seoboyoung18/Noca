<script setup>
import { useAppStore } from '../stores/app'
defineProps({
  size: { type: Number, default: 32 },
  fontSize: { type: Number, default: 14 },
})
const store = useAppStore()
</script>

<template>
  <span class="avatar" :style="{ width: size + 'px', height: size + 'px', fontSize: fontSize + 'px' }">
    <template v-if="store.hasAvatar">
      <svg viewBox="0 0 56 56" width="100%" height="100%" aria-hidden="true"><rect width="56" height="56" fill="#C9C3E8"/><circle cx="28" cy="22" r="10" fill="#6C58EB"/><path d="M8 56c2-12 10-18 20-18s18 6 20 18z" fill="#4E36E4"/></svg>
    </template>
    <template v-else>김</template>
    <slot />
  </span>
</template>

<style scoped>
.avatar { position: relative; flex: 0 0 auto; border-radius: 50%; background: var(--primary-100); color: var(--primary); font-weight: 700; display: flex; align-items: center; justify-content: center; overflow: visible; }
.avatar svg { border-radius: 50%; }
</style>
