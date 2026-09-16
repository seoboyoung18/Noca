<script setup>
// 사고 미리보기 카드 — 차량명·연식·접수일·상태 배지와 대표 이미지(첫 사진 썸네일).
// 서버는 차종 카탈로그 사진을 주지 않으므로 이미지는 사용자가 올린 첫 사진이 유일하다. 없으면 자리표시.
// 업로드 화면 상단·홈 최근 사고 등에서 같은 모양으로 쓴다.
import { computed, ref } from 'vue'
import { vehicleName } from '../data/vehicles'
import { accidentDate, accidentStatus } from '../data/accidents'

const props = defineProps({
  /** 차량 필드 — 사고 스냅샷(accident) 또는 내 차량(vehicle). 필드명이 같아 둘 다 받는다 */
  vehicle: { type: Object, default: null },
  createdAt: { type: String, default: '' },
  status: { type: String, default: '' },
  thumbnailUrl: { type: String, default: '' },
  /** createdAt 이 없을 때 대신 보일 문구 (예: "접수 전") */
  caption: { type: String, default: '' },
  loading: { type: Boolean, default: false },
})

const broken = ref(false)
const title = computed(() => (props.loading ? '불러오는 중…' : vehicleName(props.vehicle) || '차량 정보 없음'))
const meta = computed(() => [
  props.vehicle?.modelYear ? `${props.vehicle.modelYear}년식` : '',
  props.createdAt ? `접수 ${accidentDate(props.createdAt)}` : props.caption,
].filter(Boolean).join(' · '))
</script>

<template>
  <div class="acard" :class="{ skel: loading }">
    <span class="th">
      <img v-if="thumbnailUrl && !broken" :src="thumbnailUrl" alt="" @error="broken = true">
      <svg v-else width="40" height="22" viewBox="0 0 52 28" fill="none" aria-hidden="true"><path d="M4 22V14l8-8h20l12 8v8z" fill="#B0B8C1"/></svg>
    </span>
    <span class="flex1" style="display:flex;flex-direction:column;gap:5px;min-width:0;text-align:left">
      <span class="nowrap" style="font-size:15px;font-weight:700;overflow:hidden;text-overflow:ellipsis">{{ title }}</span>
      <span class="row" style="gap:6px">
        <span v-if="meta" class="sub nowrap" style="font-size:12px">{{ meta }}</span>
        <span v-if="status" class="tag" :class="accidentStatus(status).cls">{{ accidentStatus(status).text }}</span>
      </span>
    </span>
  </div>
</template>

<style scoped>
.acard { width: 100%; padding: 12px 14px; border: 1px solid var(--line); border-radius: 12px; background: var(--white); display: flex; align-items: center; gap: 12px; }
.th { flex: 0 0 56px; width: 56px; height: 56px; border-radius: 8px; background: var(--text-3); display: flex; align-items: center; justify-content: center; overflow: hidden; }
.th img { width: 100%; height: 100%; object-fit: cover; }
.skel .th { background: var(--bg-2); }
</style>
