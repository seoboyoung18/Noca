<script setup>
// 검출 오버레이 — 폴리곤(반투명 채움) + 바운딩박스 코너 하이라이트(네온 글로우).
// 견적 화면과 리포트 미리보기가 <b>같은 그림</b>을 그리도록 한 곳에 둔다. 좌표는 AI 분석 축소본 픽셀이고 width×height 도 같은 기준이라
// 사진 픽셀을 그대로 viewBox 로 쓴다 — 좌표 환산이 없다. 부모는 position: relative 이고 사진이 그 상자를 채워야 한다.
//  fit=cover  : 부모가 사진을 object-fit: cover 로 자를 때(견적 화면 4:3 틀). slice 가 같은 잘림을 만들어 도형이 사진에 붙어 있다
//  fit=contain: 사진을 원래 비율 그대로 보일 때(리포트 미리보기). 사진 상자와 1:1 로 겹친다
import { cornerPath } from '../data/estimates'

defineProps({
  width: { type: Number, default: 0 },
  height: { type: Number, default: 0 },
  /** [{ id, rect: {x,y,w,h}|null, polygons: ['x,y x,y …', …] }] — data/estimates 의 detectionMarks() 결과 */
  marks: { type: Array, default: () => [] },
  fit: { type: String, default: 'cover' },
})
</script>

<template>
  <svg v-if="width && height && marks.length" class="ovl" :viewBox="`0 0 ${width} ${height}`"
    :preserveAspectRatio="fit === 'cover' ? 'xMidYMid slice' : 'xMidYMid meet'" aria-hidden="true">
    <g v-for="m in marks" :key="m.id">
      <polygon v-for="(pts, i) in m.polygons" :key="i" class="poly" :points="pts" />
      <path v-if="m.rect" class="bx" :d="cornerPath(m.rect)" />
    </g>
  </svg>
</template>

<style scoped>
/* 선 굵기는 사진 크기와 무관하게 일정해야 해서 non-scaling-stroke */
.ovl { position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; animation: fadein .2s ease-out; }
/* 네온 글로우 — 밝은 하늘색 선에 같은 색 그림자를 겹쳐 빛 번짐을 만든다. 흰 차체 위에서도 읽히도록 가장 안쪽 그림자는 진하게 */
.poly { fill: rgba(56,189,248,.16); stroke: #7DD3FC; stroke-width: 1.2; stroke-linejoin: round; vector-effect: non-scaling-stroke;
  filter: drop-shadow(0 0 1px rgba(2,132,199,.9)) drop-shadow(0 0 5px rgba(56,189,248,.65)); }
/* 코너 하이라이트 — 네 모서리 꺾쇠. 짧은 선이라 조금 두껍게 두고 끝을 둥글린다 */
.bx { fill: none; stroke: #BAE6FD; stroke-width: 2.4; stroke-linecap: round; stroke-linejoin: round; vector-effect: non-scaling-stroke;
  filter: drop-shadow(0 0 1px rgba(2,132,199,1)) drop-shadow(0 0 4px rgba(56,189,248,.95)) drop-shadow(0 0 10px rgba(56,189,248,.65)); }
</style>
