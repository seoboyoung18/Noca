<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import LogoMark from '../components/LogoMark.vue'

const router = useRouter()

/* 카드 슬라이드 — 디자인 스크립트 이식 (300px 카드 + 12px 간격, 앞뒤 클론으로 무한 루프) */
const STEP = 312
const N = 3
const cards = [
  { img: '/assets/front-door-dent.png', alt: '앞도어 눌림 인식 사진', boxes: [{ l: 72, t: 46, w: 113, h: 76, label: '앞도어 · 눌림' }] },
  { img: '/assets/front-fender-scratch.png', alt: '앞휀더 긁힘 인식 사진', boxes: [{ l: 72, t: 46, w: 169, h: 86, label: '앞휀더 · 긁힘' }, { l: 41, t: 122, w: 31, h: 20, label: '앞범퍼 · 찍힘', below: true }] },
  { img: '/assets/rear-bumper-broken.png', alt: '뒷범퍼 깨짐 인식 사진', boxes: [{ l: 82, t: 30, w: 144, h: 150, label: '뒷범퍼 · 깨짐' }] },
]
const track = [cards[2], ...cards, cards[0]]

const pos = ref(1)
const dx = ref(0)
const dragging = ref(false)
const snap = ref(false)
let x0 = 0, timer, snapT

const real = computed(() => (((pos.value - 1) % N) + N) % N)
const trackStyle = computed(() => ({
  transform: `translateX(${-pos.value * STEP + dx.value}px)`,
  transition: dragging.value || snap.value ? 'none' : 'transform .45s cubic-bezier(.22,.7,.3,1)',
}))

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

onMounted(arm)
onUnmounted(() => { clearInterval(timer); clearTimeout(snapT) })
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

    <div class="body scroll" style="padding:0">
      <section class="carousel">
        <div class="viewport" @pointerdown="onDown" @pointermove="onMove" @pointerup="onUp" @pointercancel="onUp">
          <div class="track" :style="trackStyle">
            <div v-for="(c, i) in track" :key="i" class="cardimg">
              <img :src="c.img" :alt="c.alt" draggable="false">
              <div v-for="(b, j) in c.boxes" :key="j" class="box" :style="{ left: b.l + 'px', top: b.t + 'px', width: b.w + 'px', height: b.h + 'px' }">
                <span class="boxlbl" :class="{ below: b.below }">{{ b.label }}</span>
              </div>
            </div>
          </div>
        </div>
        <div class="dots">
          <span v-for="n in 3" :key="n" :class="{ on: real === n - 1 }"></span>
        </div>
      </section>

      <section style="padding:22px 20px 0">
        <h1 class="hero">정비소 가기 전에,<br>수리비부터 알고 가세요</h1>
        <p class="sub" style="margin-top:8px">사진 한 장이면 손상 부위와 예상 수리비를 확인할 수 있어요.</p>
      </section>

      <section style="padding:18px 20px 0">
        <button class="btn lg" @click="router.push('/login')">사진으로 견적 확인</button>
      </section>

      <section style="padding:26px 20px 0">
        <h2 class="h2">3단계면 끝나요</h2>
        <p class="sub" style="margin-top:4px">사진만 찍으면 나머지는 저희가 합니다</p>
        <div class="steps">
          <div class="stp"><b>1</b><span class="t">촬영</span><span class="d">손상 부위를<br>찍어요</span></div>
          <div class="arr">›</div>
          <div class="stp"><b>2</b><span class="t">분석</span><span class="d">부위와 정도를<br>인식해요</span></div>
          <div class="arr">›</div>
          <div class="stp"><b>3</b><span class="t">견적</span><span class="d">예상 금액을<br>받아요</span></div>
        </div>
      </section>
      <div style="height:24px"></div>
    </div>

    <footer class="lfoot">
      <p>예상 견적은 사진 분석 기반의 참고용 정보이며, 실제 수리비는 정비소 점검 결과에 따라 달라질 수 있습니다.</p>
    </footer>
  </Screen>
</template>

<style scoped>
.hdr.top { height: 56px; flex-basis: 56px; padding: 0 20px; justify-content: space-between; }
.brand { font-size: 20px; font-weight: 700; color: var(--text); letter-spacing: -0.03em; }
.login { font-size: 13px; font-weight: 500; color: var(--text-2); }
.carousel { padding: 16px 0 0 20px; }
.viewport { width: calc(100% - 20px); max-width: 320px; overflow: hidden; touch-action: pan-y; cursor: grab; user-select: none; }
.viewport:active { cursor: grabbing; }
.track { display: flex; gap: 12px; will-change: transform; }
.cardimg { flex: 0 0 300px; height: 200px; border-radius: 12px; overflow: hidden; background: var(--bg); border: 1px solid var(--line); position: relative; }
.cardimg img { width: 100%; height: 100%; object-fit: cover; user-select: none; -webkit-user-drag: none; }
.box { position: absolute; border: 2px solid var(--primary); background: rgba(78,54,228,.08); border-radius: 4px; }
.boxlbl { position: absolute; left: -2px; bottom: calc(100% + 6px); white-space: nowrap; background: var(--primary); color: #fff; font-size: 12px; font-weight: 500; padding: 4px 8px; border-radius: 6px; }
.boxlbl.below { bottom: auto; top: calc(100% + 6px); }
.dots { width: 300px; display: flex; gap: 5px; justify-content: center; padding-top: 14px; }
.dots span { width: 16px; height: 4px; border-radius: 8px; background: var(--line); transition: background .3s; }
.dots span.on { background: var(--primary); }
.hero { font-size: 24px; line-height: 1.35; font-weight: 800; color: var(--text); letter-spacing: -0.035em; }
.h2 { font-size: 17px; font-weight: 600; color: var(--text); }
.steps { margin-top: 16px; padding: 16px 12px; background: var(--bg-2); border-radius: 12px; display: grid; grid-template-columns: 1fr 12px 1fr 12px 1fr; align-items: start; }
.stp { display: flex; flex-direction: column; align-items: center; gap: 8px; }
.stp b { width: 40px; height: 40px; border-radius: 20px; background: var(--primary-100); color: var(--primary-deep); font-size: 15px; font-weight: 800; display: flex; align-items: center; justify-content: center; }
.stp .t { font-size: 13px; font-weight: 700; color: var(--text); }
.stp .d { font-size: 11px; color: var(--text-3); text-align: center; line-height: 1.4; }
.arr { align-self: center; margin-top: -22px; color: var(--line-2); font-size: 13px; text-align: center; }
.lfoot { flex: 0 0 auto; padding: 14px 20px calc(var(--safe-b) - 16px); border-top: 1px solid var(--line); }
.lfoot p { font-size: 11px; line-height: 1.5; color: var(--text-3); }
</style>
