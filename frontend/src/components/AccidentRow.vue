<script setup>
// 사고 이력 카드 한 장 — 사고 이력 화면과 홈 "최근 사고" 미리보기가 같은 카드를 쓴다(정보·배치 동일).
// 정사각 썸네일 | 차량명 + 상태 배지 · 사고 설명(견적 부위·손상 유형, lib/accidentDesc) | 오른쪽 동작(slot action — PDF 버튼 등)
import { ref, watch } from 'vue'
import { useAccidentDesc } from '../lib/accidentDesc'
import { vehicleName } from '../data/vehicles'
import { accidentStatus } from '../data/accidents'

const props = defineProps({
  a: { type: Object, required: true },
  /** false 면 카드 클릭 이동을 막는다(숨기기 모드 등) */
  clickable: { type: Boolean, default: true },
})
const emit = defineEmits(['open'])
const { ensure, text } = useAccidentDesc()
const broken = ref(false) // 만료·404 로 깨진 썸네일 → 자리표시
watch(() => props.a, (a) => { if (a) ensure([a]) }, { immediate: true })
function open() { if (props.clickable) emit('open', props.a) }
</script>

<template>
  <div class="card arow" :class="{ clickable }" :role="clickable ? 'link' : null" :tabindex="clickable ? 0 : -1" @click="open" @keydown.enter="open">
    <span class="th">
      <img v-if="a.thumbnailUrl && !broken" :src="a.thumbnailUrl" alt="" @error="broken = true">
      <svg v-else width="44" height="24" viewBox="0 0 52 28" fill="none" aria-hidden="true"><path d="M4 22V14l8-8h20l12 8v8z" fill="#B0B8C1"/></svg>
    </span>
    <span class="flex1" style="display:flex;flex-direction:column;align-items:flex-start;gap:6px;min-width:0">
      <span class="row" style="gap:8px;max-width:100%">
        <span class="nowrap" style="font-size:16px;font-weight:700;overflow:hidden;text-overflow:ellipsis">{{ vehicleName(a) }}</span>
        <span class="tag" style="flex:0 0 auto" :class="accidentStatus(a.status).cls">{{ accidentStatus(a.status).text }}</span>
      </span>
      <span class="sub" style="font-size:13px;line-height:1.35;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden">{{ text(a) }}</span>
    </span>
    <slot name="action" />
  </div>
</template>

<style scoped>
.arow { margin-top: 8px; padding: 14px; display: flex; align-items: center; gap: 14px; }
.arow.clickable { cursor: pointer; }
.th { flex: 0 0 76px; width: 76px; height: 76px; border-radius: 12px; background: var(--text-3); display: flex; align-items: center; justify-content: center; overflow: hidden; }
.th img { width: 100%; height: 100%; object-fit: cover; }
</style>
