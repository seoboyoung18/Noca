<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import LogoMark from '../components/LogoMark.vue'

const router = useRouter()

/* 카드 슬라이드 — 디자인 스크립트 이식 (앞뒤 클론으로 무한 루프)
 * 세 장은 1 촬영 → 2 분석 → 3 리포트 순서다 (S15P21A307-559). 사진 아래 스테퍼(.stepper)가 점 표시 대신 같은 순서로 함께 움직인다.
 * 카드는 <b>한 장이 뷰포트를 꽉 채운다</b>(화면 폭 − 양옆 20px). 예전엔 300px 카드에 320px 뷰포트라 다음 장이 8px 비쳤는데,
 * 그 자락을 없애고 아래 문단·버튼과 좌우 선을 맞췄다. 폭은 화면마다 다르니 ResizeObserver 로 재서 이동 거리(STEP)를 만든다.
 * 인식 박스는 분석 카드에만 둔다. 촬영 카드는 화면 속 촬영 프레임이, 리포트 카드는 폰 속 리포트가 이미 뜻을 말하지만
 * 분석 카드는 원본 사진 그대로라 박스가 없으면 그냥 긁힌 차다.
 * 박스 좌표는 카드 기준 %. 카드가 3:2 로 고정돼 있어 폭이 달라져도 사진과 박스가 같이 움직인다.
 * 분석 사진은 4:3 이라 cover 로 위아래가 조금 잘린다 — 그걸 감안한 값이다. */
const GAP = 12
const N = 3
const cards = [
  { step: 1, title: '촬영', desc: '차량 손상 부위를 카메라로 찍어요', img: '/assets/landing-shoot.webp', alt: '스마트폰으로 차량 손상 부위를 촬영하는 모습', boxes: [] },
  { step: 2, title: '분석', desc: 'AI가 부위와 손상 유형을 인식해요', img: '/assets/landing-analyze.webp', alt: '앞휀더 긁힘을 인식한 사진', boxes: [{ l: 33.3, t: 24, w: 53.3, h: 35, label: '앞휀더 · 긁힘' }] },
  { step: 3, title: '리포트', desc: '예상 수리비와 근거를 리포트로 받아요', img: '/assets/landing-report2.webp', alt: '스마트폰에 표시된 예상 수리비 리포트', boxes: [] },
]

/* 뷰포트 폭 — 카드 한 장의 폭이자 이동 거리의 밑값. 처음 값 320 은 360px 화면 기준(가장 흔함), 마운트 직후 실측으로 덮는다 */
const viewportEl = ref(null)
const vw = ref(320)
const STEP = computed(() => vw.value + GAP)
let ro
const track = [cards[2], ...cards, cards[0]]

const pos = ref(1)
const dx = ref(0)
const dragging = ref(false)
const snap = ref(false)
let x0 = 0, timer, snapT

const real = computed(() => (((pos.value - 1) % N) + N) % N)
const trackStyle = computed(() => ({
  '--cw': `${vw.value}px`,
  transform: `translateX(${-pos.value * STEP.value + dx.value}px)`,
  transition: dragging.value || snap.value ? 'none' : 'transform .45s cubic-bezier(.22,.7,.3,1)',
}))
/** 폭이 바뀌면 이동 거리도 바뀐다 — 그 순간만 전환을 끄고 자리를 옮긴다(안 그러면 카드가 미끄러진다) */
function measure() {
  const w = viewportEl.value?.clientWidth
  if (!w || w === vw.value) return
  snap.value = true
  vw.value = w
  requestAnimationFrame(() => requestAnimationFrame(() => { snap.value = false }))
}

function arm() {
  clearInterval(timer)
  timer = setInterval(() => { if (!dragging.value) go(1) }, 2600)
}
function go(dir) {
  clearTimeout(snapT)
  pos.value += dir
  dx.value = 0
  dragging.value = false
  const p = pos.value
  if (p === 0 || p === N + 1) {
    snapT = setTimeout(() => {
      snap.value = true
      pos.value = p === 0 ? N : 1
      requestAnimationFrame(() => requestAnimationFrame(() => { snap.value = false }))
    }, 460)
  }
}
/** 스테퍼의 단계를 누르면 그 슬라이드로 간다. 클론 위치(0·N+1)에 있어도 실제 위치(1..N)로 바로 옮기니 되감기 예약은 지운다 */
function jump(i) {
  clearTimeout(snapT)
  dragging.value = false
  dx.value = 0
  pos.value = i + 1
  arm()
}
function onDown(e) {
  clearTimeout(snapT)
  e.currentTarget.setPointerCapture?.(e.pointerId)
  x0 = e.clientX
  dragging.value = true
  dx.value = 0
}
function onMove(e) { if (dragging.value) dx.value = e.clientX - x0 }
function onUp() {
  if (!dragging.value) return
  const d = dx.value
  if (d < -40) go(1)
  else if (d > 40) go(-1)
  else { dx.value = 0; dragging.value = false }
  arm()
}

onMounted(() => {
  measure()
  ro = new ResizeObserver(measure)
  ro.observe(viewportEl.value)
  arm()
})
onUnmounted(() => { clearInterval(timer); clearTimeout(snapT); ro?.disconnect() })
</script>

<template>
  <Screen>
    <header class="hdr top">
      <div class="row" style="gap:8px">
        <LogoMark />
        <span class="brand">노카</span>
      </div>
      <button class="login" @click="router.push('/login')">로그인</button>
    </header>

    <!-- 한 화면 구성(S15P21A307-559): 캐로셀·문구·CTA 만 두고 남는 높이를 CTA 위아래에 3:2 로 나눈다.
         버튼이 아래쪽(엄지 자리)에 앉되 법적 고지 푸터에 붙지는 않는다. 내용이 화면보다 길어지는 아주 짧은 기기에서만 스크롤이 생긴다 -->
    <div class="body col fixed" style="padding:0">
      <section class="carousel">
        <div ref="viewportEl" class="viewport" @pointerdown="onDown" @pointermove="onMove" @pointerup="onUp" @pointercancel="onUp">
          <div class="track" :style="trackStyle">
            <div v-for="(c, i) in track" :key="i" class="cardimg">
              <img :src="c.img" :alt="c.alt" draggable="false">
              <div v-for="(b, j) in c.boxes" :key="j" class="box" :style="{ left: b.l + '%', top: b.t + '%', width: b.w + '%', height: b.h + '%' }">
                <span class="boxlbl" :class="{ below: b.below }">{{ b.label }}</span>
              </div>
            </div>
          </div>
        </div>
        <!-- 슬라이드 표시 — 점 대신 단계 스테퍼. 지금 보이는 슬라이드의 단계가 켜지고 한 줄 설명이 따라 바뀐다. 단계를 누르면 그 슬라이드로 -->
        <div class="stepper" aria-label="이용 단계">
          <ol class="sr">
            <li v-for="(c, i) in cards" :key="c.step" :class="{ on: real === i, done: i < real }">
              <button type="button" :aria-current="real === i ? 'step' : null" @click="jump(i)"><b>{{ c.step }}</b><span>{{ c.title }}</span></button>
            </li>
          </ol>
          <Transition name="sdfade" mode="out-in">
            <p :key="real" class="sd" aria-live="polite">{{ cards[real].desc }}</p>
          </Transition>
        </div>
      </section>

      <section style="padding:22px 20px 0">
        <h1 class="hero">정비소 가기 전,<br>수리 견적 확인해보세요</h1>
        <p class="sub" style="margin-top:8px">사진 한 장이면 예상 수리 견적을 확인할 수 있어요.</p>
      </section>

      <div class="gap a"></div>
      <!-- 받는 것 세 가지 — 위쪽(사진·스테퍼)이 "어떻게" 를 말하니 여기는 "무엇을 얻는지". 실제 있는 기능만 적는다.
           짧은 화면(700 미만)에서는 접는다 — 자리가 40~50px 뿐이고, 문장이 이미 핵심을 말한다 -->
      <ul class="perks" aria-label="받는 것">
        <li>
          <i><svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true"><circle cx="12" cy="12" r="8.5" stroke="currentColor" stroke-width="1.7"/><path d="M8.2 8.5l1.6 7 2.2-5.2 2.2 5.2 1.6-7M8 12.4h8" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg></i>
          <span>예상 수리 견적</span>
        </li>
        <li>
          <i><svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true"><rect x="4.5" y="3.5" width="15" height="17" rx="2" stroke="currentColor" stroke-width="1.7"/><path d="M9 2.5h6v2.5H9zM8.3 12.3l2.4 2.4 5-5" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg></i>
          <span>정비 체크리스트</span>
        </li>
        <li>
          <i><svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 21.5c4.6-5.2 6.9-9 6.9-11.5a6.9 6.9 0 1 0-13.8 0c0 2.5 2.3 6.3 6.9 11.5Z" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/><circle cx="12" cy="9.7" r="2.6" stroke="currentColor" stroke-width="1.7"/></svg></i>
          <span>가까운 정비소 찾기</span>
        </li>
      </ul>
      <div class="gap m"></div>
      <section style="padding:0 20px">
        <button class="btn lg" @click="router.push('/login')">사진으로 견적 확인</button>
      </section>
      <div class="gap b"></div>
    </div>

    <footer class="lfoot">
      <p>예상 견적은 사진 분석 기반의 참고용 정보이며, 실제 수리비는 정비소 점검 결과에 따라 달라질 수 있습니다.</p>
    </footer>
  </Screen>
</template>

<style scoped>
/* 홈 상단과 같은 높이에 맞춘다 — 홈은 본문 padding-top 20 아래 32px 로고 줄이라, 여기도 로고 줄 위가 20px 에 오게 위 여백 8 을 더한다 */
.hdr.top { height: 64px; flex-basis: 64px; padding: 8px 20px 0; justify-content: space-between; }
.brand { font-size: 20px; font-weight: 700; color: var(--text); letter-spacing: -0.03em; }
.login { font-size: 13px; font-weight: 500; color: var(--text-2); }
.carousel { padding: 16px 20px 0; }
/* 뷰포트는 카드 한 장 폭 — 다음 장이 비치지 않는다. 좌우 20px 는 아래 문단·버튼과 같은 여백 */
.viewport { width: 100%; overflow: hidden; touch-action: pan-y; cursor: grab; user-select: none; }
.viewport:active { cursor: grabbing; }
.track { display: flex; gap: 12px; will-change: transform; }
.cardimg { flex: 0 0 var(--cw, 320px); aspect-ratio: 3 / 2; border-radius: 12px; overflow: hidden; background: var(--bg); border: 1px solid var(--line); position: relative; }
.cardimg img { width: 100%; height: 100%; object-fit: cover; user-select: none; -webkit-user-drag: none; }
.box { position: absolute; border: 2px solid var(--primary); background: rgba(78,54,228,.08); border-radius: 4px; }
.boxlbl { position: absolute; left: -2px; bottom: calc(100% + 6px); white-space: nowrap; background: var(--primary); color: #fff; font-size: 12px; font-weight: 500; padding: 4px 8px; border-radius: 6px; }
.boxlbl.below { bottom: auto; top: calc(100% + 6px); }
.hero { font-size: 24px; line-height: 1.35; font-weight: 800; color: var(--text); letter-spacing: -0.035em; }
/* 남는 높이 분배 — 문구→혜택 1 : 혜택→버튼 1 : 버튼→푸터 2.56. 혜택 행 위아래 여백을 같게 두고, 버튼은 그보다 아래(엄지 자리)에 앉는다.
   2.56 은 S24(780) 에서 버튼이 제 높이의 절반(25px)만큼 올라온 자리 — 29 / 29 / 75. 최소 여백을 둬서 화면이 짧아도 서로 붙지 않는다 */
.body.col > * { flex-shrink: 0; }
.gap { flex-shrink: 1; }
.gap.a { flex: 1 1 0; min-height: 20px; }
.gap.m { flex: 1 1 0; min-height: 20px; }
.gap.b { flex: 2.56 1 0; min-height: 20px; }

/* 받는 것 세 칸 — 연한 브랜드색 동그라미 안 아이콘 + 한 줄 라벨 */
.perks { list-style: none; margin: 0; padding: 0 20px; display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 8px; }
.perks li { display: flex; flex-direction: column; align-items: center; gap: 7px; }
.perks i { width: 40px; height: 40px; border-radius: 20px; background: var(--primary-50); color: var(--primary); display: flex; align-items: center; justify-content: center; }
.perks span { font-size: 12px; font-weight: 600; color: var(--text-2); white-space: nowrap; letter-spacing: -0.01em; }
@media (max-height: 699px) {
  .perks, .gap.m { display: none; }
}

/* 단계 표시 — 점(dots) 대신 슬라이드 표시를 맡는다. 번호 동그라미 + 라벨 세 개를 선으로 잇고, 켜진 단계만 브랜드색, 지난 단계는 연한 브랜드색 테두리 */
.stepper { padding-top: 14px; }
.sr { display: flex; align-items: center; justify-content: center; list-style: none; margin: 0; padding: 0; }
.sr li { display: flex; align-items: center; }
.sr li + li::before { content: ""; width: 26px; height: 1.5px; margin: 0 8px; background: var(--line); transition: background .3s; }
.sr li.on::before, .sr li.done::before { background: var(--primary-200); }
.sr button { display: flex; align-items: center; gap: 6px; padding: 4px 2px; font-size: 13px; font-weight: 600; color: var(--text-3); transition: color .3s; }
.sr b { width: 22px; height: 22px; border-radius: 11px; border: 1.5px solid var(--line-2); background: #fff; color: var(--text-3); font-size: 11px; font-weight: 800;
  display: flex; align-items: center; justify-content: center; transition: background .3s, border-color .3s, color .3s; }
.sr li.on button { color: var(--text); }
.sr li.on b { background: var(--primary); border-color: var(--primary); color: #fff; }
.sr li.done b { border-color: var(--primary-200); color: var(--primary); }
.sd { margin: 8px 0 0; text-align: center; font-size: 13px; line-height: 1.5; color: var(--text-2); }
.sdfade-enter-active, .sdfade-leave-active { transition: opacity .22s ease; }
.sdfade-enter-from, .sdfade-leave-to { opacity: 0; }
.lfoot { flex: 0 0 auto; padding: 14px 20px calc(var(--safe-b) - 16px); border-top: 1px solid var(--line); }
.lfoot p { font-size: 11px; line-height: 1.5; color: var(--text-3); }
</style>
