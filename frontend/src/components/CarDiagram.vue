<script setup>
// 차 도면에서 부위를 고른다 — 목업 S07_견적_B안전개도의 전개도 버튼을 그대로 옮겼다(위에서 본 차체 + 운전석·조수석 옆면, 스텝).
// 도면에 없는 부품(사이드미러·유리·휠·램프·필러·차량 하부)은 아래 "사이드미러/유리/휠/라이트" 버튼을 눌러 칩으로 고른다.
// 라벨은 서버 부품명(data/parts.js)을 쓰고, 옆면은 열 머리([운전석]/[조수석])가 좌우를 말하므로 "(좌)/(우)" 는 뗀다.
import { computed, ref } from 'vue'
import { AI_PARTS, partName } from '../data/parts'

const props = defineProps({ modelValue: { type: String, default: '' } })
const emit = defineEmits(['update:modelValue'])
function pick(code) { emit('update:modelValue', props.modelValue === code ? '' : code) }
const on = (code) => props.modelValue === code
const short = (code) => partName(code).replace(/\((좌|우)\)$/, '')

/* 누를 수 있는 부위 — 목업 SVG(viewBox 21 19 278 313)의 path 와 라벨 좌표 그대로. rot 는 스텝(로커 패널)처럼 세로로 쓰는 라벨 */
const PARTS = [
  { code: 'FRONT_BUMPER', x: 158.9, y: 47.3, d: 'M123.6 26.2 Q158.9 21.1 194.2 26.2 Q201.5 27.6 201.5 34.9 L201.5 64.7 Q201.5 69.1 197.1 69.1 L120.7 69.1 Q116.4 69.1 116.4 64.7 L116.4 34.9 Q116.4 27.6 123.6 26.2 Z' },
  { code: 'BONNET', x: 158.9, y: 102.9, d: 'M129.1 87.3 Q158.9 72.7 188.7 87.3 Q194.9 109.1 193.5 129.1 Q158.9 123.6 124.4 129.1 Q122.9 109.1 129.1 87.3 Z' },
  { code: 'ROOF', x: 158.9, y: 194.5, d: 'M130.9 165.5 L186.9 165.5 L186.9 223.6 L130.9 223.6 Z' },
  { code: 'TRUNK_LID', x: 158.9, y: 267.3, d: 'M125.5 254.5 Q158.9 260.4 192.4 254.5 L192.4 277.1 Q158.9 282.9 125.5 277.1 Z' },
  { code: 'REAR_BUMPER', x: 158.9, y: 307.3, d: 'M120.7 287.3 L197.1 287.3 Q201.5 287.3 201.5 291.6 L201.5 316.4 Q201.5 323.6 194.2 324.4 Q158.9 329.5 123.6 324.4 Q116.4 323.6 116.4 316.4 L116.4 291.6 Q116.4 287.3 120.7 287.3 Z' },
  { code: 'FRONT_FENDER_L', x: 75.6, y: 113.5, d: 'M48 92.7 Q48 89.1 51.6 89.1 L71.3 87.3 L83.6 89.5 Q101.8 112.7 103.3 138.2 L48 138.2 Z' },
  { code: 'FRONT_DOOR_L', x: 75.6, y: 155.6, d: 'M48 139.6 L103.3 139.6 Q104 160 104 182.5 L48 182.5 Z' },
  { code: 'REAR_DOOR_L', x: 75.6, y: 200, d: 'M48 184 L104 184 Q104 203.6 102.5 225.5 L48 225.5 Z' },
  { code: 'REAR_FENDER_L', x: 75.6, y: 241.8, d: 'M48 226.9 L102.5 226.9 Q98.9 252.4 83.6 264.7 Q72 274.2 54.5 272 Q48 271.3 48 265.5 Z' },
  { code: 'ROCKER_PANEL_L', x: 40, y: 174.5, rot: true, d: 'M33.5 139.6 L46.5 139.6 L46.5 225.5 L33.5 225.5 Z' },
  { code: 'FRONT_FENDER_R', x: 242.2, y: 113.5, d: 'M269.8 92.7 Q269.8 89.1 266.2 89.1 L246.5 87.3 L234.2 89.5 Q216 112.7 214.5 138.2 L269.8 138.2 Z' },
  { code: 'FRONT_DOOR_R', x: 242.2, y: 155.6, d: 'M269.8 139.6 L214.5 139.6 Q213.8 160 213.8 182.5 L269.8 182.5 Z' },
  { code: 'REAR_DOOR_R', x: 242.2, y: 200, d: 'M269.8 184 L213.8 184 Q213.8 203.6 215.3 225.5 L269.8 225.5 Z' },
  { code: 'REAR_FENDER_R', x: 242.2, y: 241.8, d: 'M269.8 226.9 L215.3 226.9 Q218.9 252.4 234.2 264.7 Q245.8 274.2 263.3 272 Q269.8 271.3 269.8 265.5 Z' },
  { code: 'ROCKER_PANEL_R', x: 277.8, y: 174.5, rot: true, d: 'M271.3 139.6 L284.4 139.6 L284.4 225.5 L271.3 225.5 Z' },
]
/* 도면에 없는 부품 — 아래 버튼을 누르면 ① 좌·우를 먼저 고르고 ② 그 쪽의 부품을 카테고리 토글에서 고른다.
 * 유리·차량 하부처럼 좌우가 없는 부품은 어느 쪽을 골라도 나온다. */
const GROUPS = [
  { code: 'MIRROR', text: '사이드미러', parts: ['SIDE_MIRROR_L', 'SIDE_MIRROR_R'] },
  { code: 'GLASS', text: '유리', parts: ['WINDSHIELD', 'REAR_WINDSHIELD'] },
  { code: 'WHEEL', text: '휠', parts: ['FRONT_WHEEL_L', 'FRONT_WHEEL_R', 'REAR_WHEEL_L', 'REAR_WHEEL_R'] },
  { code: 'LAMP', text: '라이트', parts: ['HEAD_LIGHT_L', 'HEAD_LIGHT_R', 'REAR_LAMP_L', 'REAR_LAMP_R'] },
  { code: 'PILLAR', text: '필러', parts: ['A_PILLAR_L', 'A_PILLAR_R', 'C_PILLAR_L', 'C_PILLAR_R'] },
  { code: 'UNDER', text: '차량 하부', parts: ['UNDERCARRIAGE'] },
]
const OTHERS = GROUPS.flatMap((g) => g.parts)
const SIDES = [{ code: 'L', text: '좌 (운전석)' }, { code: 'R', text: '우 (조수석)' }]
const sideOf = (code) => (/_L$/.test(code) ? 'L' : /_R$/.test(code) ? 'R' : '') // 좌우가 없는 부품은 ''
const othersOpen = ref(false)
const side = ref('') // ① 좌·우
const openGroup = ref('') // ② 펼친 카테고리 — 한 번에 하나
const otherPicked = computed(() => OTHERS.includes(props.modelValue))
const partsOf = (g) => g.parts.filter((c) => !sideOf(c) || sideOf(c) === side.value) // 고른 쪽 부품 + 좌우 없는 부품
const pickedIn = (g) => partsOf(g).find((c) => c === props.modelValue) // 이 카테고리 안에서 고른 부품
function pickSide(code) {
  if (side.value === code) return
  side.value = code
  if (sideOf(props.modelValue) && sideOf(props.modelValue) !== code) emit('update:modelValue', '') // 반대쪽 부품을 골라 뒀으면 푼다
  if (!openGroup.value) openGroup.value = GROUPS[0].code
}
function toggleGroup(code) { openGroup.value = openGroup.value === code ? '' : code }
function toggleOthers() {
  othersOpen.value = !othersOpen.value
  if (!othersOpen.value) return
  if (otherPicked.value && sideOf(props.modelValue)) side.value = sideOf(props.modelValue) // 고른 게 있으면 그쪽을 펼쳐 둔다
  if (side.value && !openGroup.value) openGroup.value = GROUPS.find((g) => pickedIn(g))?.code || GROUPS[0].code
}
if (import.meta.env.DEV) { // 32종이 빠짐없이 놓였는지 — 개발 중에만 확인
  const all = new Set([...PARTS.map((p) => p.code), ...OTHERS]); const miss = AI_PARTS.filter((p) => !all.has(p.code)); if (miss.length) console.warn('CarDiagram: 도면에 없는 부품', miss.map((p) => p.code))
}
</script>

<template>
  <div class="cd">
    <div class="hd"><span style="left:14%">[운전석]</span><span style="left:85.3%">[조수석]</span></div>
    <svg viewBox="21 19 278 313" width="100%" aria-label="차 도면에서 부위 고르기">
      <!-- 바탕(누를 수 없음): 휠·차체·유리·필러·사이드미러·도어 손잡이 -->
      <circle cx="39.3" cy="110.9" r="16.4" fill="#D3D5DA"></circle><circle cx="39.3" cy="240" r="16.4" fill="#D3D5DA"></circle><circle cx="279.3" cy="110.9" r="16.4" fill="#D3D5DA"></circle><circle cx="279.3" cy="240" r="16.4" fill="#D3D5DA"></circle>
      <path d="M123.6 83.6 Q158.9 69.1 194.2 83.6 Q199.3 145.5 199.3 203.6 Q199.3 254.5 194.2 280 Q158.9 285.8 123.6 280 Q118.5 254.5 118.5 203.6 Q118.5 145.5 123.6 83.6 Z" fill="#E8E9EC"></path>
      <path d="M45.8 84.4 Q54.5 74.9 72.7 77.1 L87.3 83.6 Q109.1 109.1 109.1 170.9 Q109.1 232.7 87.3 269.1 Q72.7 282.9 54.5 280 Q45.8 278.5 45.8 269.1 Z" fill="#E8E9EC"></path>
      <path d="M272 84.4 Q263.3 74.9 245.1 77.1 L230.5 83.6 Q208.7 109.1 208.7 170.9 Q208.7 232.7 230.5 269.1 Q245.1 282.9 263.3 280 Q272 278.5 272 269.1 Z" fill="#E8E9EC"></path>
      <path d="M124.4 130.2 Q158.9 125.1 193.5 130.2 L187.6 164.4 L130.2 164.4 Z" fill="#C8CBD1"></path>
      <path d="M130.2 224.7 L187.6 224.7 L192.4 253.5 Q158.9 258.9 125.5 253.5 Z" fill="#C8CBD1"></path>
      <path d="M120.7 134.5 L129.5 165.5 L129.5 223.6 L121.5 250.9 Q119.3 203.6 120.7 134.5 Z" fill="#C8CBD1"></path>
      <path d="M197.1 134.5 L188.4 165.5 L188.4 223.6 L196.4 250.9 Q198.5 203.6 197.1 134.5 Z" fill="#C8CBD1"></path>
      <path d="M118.5 145.5 L111.3 149.1 Q109.1 156.4 114.9 158.5 L119.3 158.5 Z" fill="#D3D5DA"></path>
      <path d="M199.3 145.5 L206.5 149.1 Q208.7 156.4 202.9 158.5 L198.5 158.5 Z" fill="#D3D5DA"></path>
      <rect x="95.3" y="166.5" width="3.6" height="9.5" rx="1.5" fill="#D3D5DA"></rect><rect x="95.3" y="214.5" width="3.6" height="9.5" rx="1.5" fill="#D3D5DA"></rect>
      <rect x="221.1" y="166.5" width="3.6" height="9.5" rx="1.5" fill="#D3D5DA"></rect><rect x="221.1" y="214.5" width="3.6" height="9.5" rx="1.5" fill="#D3D5DA"></rect>
      <!-- 부위 버튼 -->
      <g v-for="p in PARTS" :key="p.code" class="pt" :class="{ on: on(p.code) }" role="button" :aria-label="partName(p.code)" :aria-pressed="on(p.code)" tabindex="0" @click="pick(p.code)" @keydown.enter="pick(p.code)">
        <path :d="p.d" stroke-width="1" stroke-linejoin="round" />
        <text :x="p.x" :y="p.y" text-anchor="middle" dominant-baseline="middle" :font-size="p.rot ? 10 : 12" :transform="p.rot ? `rotate(-90 ${p.x} ${p.y})` : undefined">{{ short(p.code) }}</text>
      </g>
    </svg>

    <!-- 도면에 없는 부품 — 목업의 가운데 버튼. 누르면 칩이 펼쳐진다 -->
    <div class="etc-row">
      <button type="button" class="etc" :class="{ on: otherPicked, open: othersOpen }" :aria-expanded="othersOpen" @click="toggleOthers">사이드미러/유리/휠/라이트</button>
    </div>
    <div v-if="othersOpen" class="oth">
      <!-- ① 좌·우 -->
      <div class="side">
        <span class="side-lb">위치</span>
        <button v-for="sd in SIDES" :key="sd.code" type="button" class="chip" :class="{ on: side === sd.code }" :aria-pressed="side === sd.code" @click="pickSide(sd.code)">{{ sd.text }}</button>
      </div>
      <p v-if="!side" class="hint">좌·우를 먼저 고르면 부품이 나와요</p>
    </div>
    <!-- ② 고른 쪽의 부품 — 카테고리 토글. 칩 이름은 좌우를 위에서 골랐으니 "(좌)/(우)" 를 뗀다 -->
    <div v-if="othersOpen && side" class="cats">
      <div v-for="g in GROUPS" :key="g.code" class="cat" :class="{ open: openGroup === g.code }">
        <button type="button" class="cat-hd" :aria-expanded="openGroup === g.code" @click="toggleGroup(g.code)">
          <span class="cat-nm">{{ g.text }}</span>
          <span v-if="pickedIn(g)" class="cat-pk">{{ partName(pickedIn(g)) }}</span>
          <svg class="cat-ar" width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M4 6l4 4 4-4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </button>
        <div v-if="openGroup === g.code" class="chips">
          <button v-for="code in partsOf(g)" :key="code" type="button" class="chip" :class="{ on: on(code) }" :aria-pressed="on(code)" @click="pick(code)">{{ short(code) }}</button>
        </div>
      </div>
    </div>

    <div class="sum">
      <span>선택한 부위 <b>{{ modelValue ? 1 : 0 }}</b>곳</span>
      <span class="nm">{{ modelValue ? partName(modelValue) : '' }}</span>
    </div>
  </div>
</template>

<style scoped>
.cd { margin-top: 10px; }
.hd { position: relative; height: 16px; font-size: 12px; color: var(--text-3); }
.hd span { position: absolute; top: 0; transform: translateX(-50%); }
svg { display: block; margin-top: 2px; }
.pt { cursor: pointer; }
.pt path { fill: #fff; stroke: #DADCE1; transition: fill .15s, stroke .15s; }
.pt text { font-size: 12px; font-weight: 500; fill: #4E5968; pointer-events: none; user-select: none; }
.pt:hover path { stroke: #5B6EF5; }
.pt.on path { fill: #5B6EF5; stroke: #5B6EF5; }
.pt.on text { fill: #fff; }
.etc-row { display: flex; justify-content: center; padding: 10px 0 8px; }
.etc { height: 44px; padding: 0 20px; border: 1px solid #D1D6DB; border-radius: 8px; background: #fff; font-size: 13px; font-weight: 500; color: #4E5968; }
.etc.on, .etc.open { border-color: var(--primary); background: #EEEBFD; color: var(--primary); }
.side { display: flex; align-items: center; gap: 8px; padding: 4px 2px 12px; }
.side-lb { flex: 0 0 auto; margin-right: 2px; font-size: 12px; color: var(--text-3); }
.hint { margin: 0 0 12px; font-size: 12px; color: var(--text-3); }
.cats { display: flex; flex-direction: column; border-top: 1px solid var(--line); }
.cat { border-bottom: 1px solid var(--line); }
.cat-hd { width: 100%; display: flex; align-items: center; gap: 8px; padding: 13px 4px; background: none; border: 0; text-align: left; font-size: 14px; color: var(--text); }
.cat-nm { flex: 1 1 auto; font-weight: 600; }
.cat-pk { font-size: 13px; font-weight: 600; color: var(--primary); white-space: nowrap; }
.cat-ar { flex: 0 0 16px; color: var(--text-3); transition: transform .2s; }
.cat.open .cat-ar { transform: rotate(180deg); }
.chips { display: flex; flex-wrap: wrap; gap: 8px; padding: 2px 4px 14px; }
.chip { padding: 9px 14px; border-radius: 999px; border: 1px solid var(--line); background: var(--white); font-size: 14px; color: var(--text); }
.chip.on { border-color: var(--primary); background: var(--primary); color: #fff; font-weight: 600; }
.sum { margin-top: 12px; display: flex; align-items: center; justify-content: space-between; font-size: 12px; color: var(--text-3); }
.sum b { font-weight: 700; color: var(--primary); }
.sum .nm { color: var(--text-2); }
</style>
