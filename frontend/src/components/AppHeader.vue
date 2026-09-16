<script setup>
import { useRouter } from 'vue-router'

const props = defineProps({
  title: { type: String, default: '' },
  back: { type: String, default: '' },
  // true 면 실제로 거쳐 온 이전 화면으로 돌아간다(마이페이지 → 사고 이력 → 뒤로 = 마이페이지).
  // 앱 안에서 거쳐 온 화면이 없을 때(새 탭·직접 진입)만 back 경로를 쓴다
  backHistory: { type: Boolean, default: false },
  line: { type: Boolean, default: false },
})
const router = useRouter()

function goBack() {
  // vue-router 가 history.state.back 에 직전 위치를 넣어 둔다. null 이면 이 앱에서 거쳐 온 화면이 없다는 뜻
  if (props.backHistory && router.options.history.state?.back) router.back()
  else router.push(props.back)
}
</script>

<template>
  <header class="hdr" :class="{ line }">
    <slot name="left">
      <button v-if="back" class="back" aria-label="뒤로가기" @click="goBack">
        <svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M12.5 3.5L6 10l6.5 6.5" stroke="#191F28" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
      </button>
    </slot>
    <span class="ttl">{{ title }}</span>
    <slot name="right" />
  </header>
</template>
