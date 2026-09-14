<script setup>
import { useRouter } from 'vue-router'

const props = defineProps({
  title: { type: String, default: '' },
  back: { type: String, default: '' },
  line: { type: Boolean, default: false },
})
const router = useRouter()
</script>

<template>
  <header class="hdr" :class="{ line }">
    <slot name="left">
      <button v-if="back" class="back" aria-label="뒤로가기" @click="router.push(back)">
        <svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M12.5 3.5L6 10l6.5 6.5" stroke="#191F28" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
      </button>
    </slot>
    <span class="ttl">{{ title }}</span>
    <slot name="right" />
  </header>
</template>
