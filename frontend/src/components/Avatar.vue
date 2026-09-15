<script setup>
import { computed, ref, watch } from 'vue'
import { useAppStore } from '../stores/app'
import { useAuthStore } from '../stores/auth'

defineProps({
  size: { type: Number, default: 32 },
  fontSize: { type: Number, default: 14 },
})
const store = useAppStore()
const auth = useAuthStore()

// 표시 우선순위: 서버 프로필 이미지(presigned GET) → 프로토타입 목업 아바타(store.hasAvatar) → 닉네임 첫 글자
const broken = ref(false)
watch(() => auth.profileImageUrl, () => { broken.value = false })
const src = computed(() => (!broken.value && auth.profileImageUrl) || '')
const initial = computed(() => (auth.nickname || '김').trim().charAt(0) || '김')

// presigned URL(10분)이 만료돼 이미지가 깨지면 한 번 다시 받아 본다
async function onError() {
  broken.value = true
  await auth.loadProfile()
  if (auth.profileImageUrl) broken.value = false
}
</script>

<template>
  <span class="avatar" :style="{ width: size + 'px', height: size + 'px', fontSize: fontSize + 'px' }">
    <img v-if="src" :src="src" alt="" @error="onError">
    <template v-else-if="store.hasAvatar">
      <svg viewBox="0 0 56 56" width="100%" height="100%" aria-hidden="true"><rect width="56" height="56" fill="#C9C3E8"/><circle cx="28" cy="22" r="10" fill="#6C58EB"/><path d="M8 56c2-12 10-18 20-18s18 6 20 18z" fill="#4E36E4"/></svg>
    </template>
    <template v-else>{{ initial }}</template>
    <slot />
  </span>
</template>

<style scoped>
.avatar { position: relative; flex: 0 0 auto; border-radius: 50%; background: var(--primary-100); color: var(--primary); font-weight: 700; display: flex; align-items: center; justify-content: center; overflow: visible; }
.avatar svg, .avatar img { width: 100%; height: 100%; border-radius: 50%; object-fit: cover; display: block; }
</style>
