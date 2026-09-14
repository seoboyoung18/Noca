<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import BaseButton from '../components/common/BaseButton.vue'

const router = useRouter()

function goToLogin() {
  router.push('/login')
}

// 나중에 실제 손상 인식 결과 이미지로 교체하기 쉽도록 image + boxes만 분리해둔 슬라이드 데이터.
// boxes의 top/left/width/height는 이미지 기준 퍼센트(%)라서 화면 폭이 달라져도 위치가 유지된다.
const slides = [
  {
    id: 'front-door',
    image: '/images/landing/slide-1-front-door.webp',
    alt: '앞도어 눌림 인식 사진',
    boxes: [{ label: '앞도어 · 눌림', top: 23, left: 24, width: 37.67, height: 38 }],
  },
  {
    id: 'front-fender',
    image: '/images/landing/slide-2-front-fender.webp',
    alt: '앞휀더 긁힘 인식 사진',
    boxes: [
      { label: '앞휀더 · 긁힘', top: 23, left: 24, width: 56.33, height: 43 },
      {
        label: '앞범퍼 · 찍힘',
        top: 61,
        left: 13.67,
        width: 10.33,
        height: 10,
        labelPosition: 'below',
      },
    ],
  },
  {
    id: 'rear-bumper',
    image: '/images/landing/slide-3-rear-bumper.webp',
    alt: '뒷범퍼 깨짐 인식 사진',
    boxes: [{ label: '뒷범퍼 · 깨짐', top: 15, left: 27.33, width: 48, height: 75 }],
  },
]

// 마지막 슬라이드 다음에 첫 슬라이드가 다시 이어지는 것처럼 보이게, 첫 슬라이드를 끝에 하나 더 붙여서 렌더링한다.
const loopedSlides = [...slides, { ...slides[0], id: `${slides[0].id}-loop-clone` }]

const steps = [
  { title: '촬영', desc: ['손상 부위를', '찍어요'] },
  { title: '분석', desc: ['부위와 정도를', '인식해요'] },
  { title: '견적', desc: ['예상 금액을', '받아요'] },
]

const SLIDE_WIDTH = 300
const SLIDE_GAP = 12
const SLIDE_STEP = SLIDE_WIDTH + SLIDE_GAP
const SWIPE_THRESHOLD = 40
const AUTO_SLIDE_INTERVAL_MS = 3000
const SLIDE_TRANSITION_MS = 300

// trackIndex는 loopedSlides 기준 위치. slides.length에 도달하면 복제된 첫 슬라이드를 보여준 뒤
// 트랜지션이 끝나자마자 티 안 나게 0번으로 되돌린다(끊김 없는 무한 루프처럼 보이게 하는 트릭).
const trackIndex = ref(0)
const activeIndex = computed(() => trackIndex.value % slides.length)
const dragOffset = ref(0)
const isDragging = ref(false)
const isJumping = ref(false)
let pointerStartX = 0
let autoSlideTimer = null
let loopResetTimer = null

const reducedMotionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')

function clearLoopResetTimer() {
  if (loopResetTimer) {
    clearTimeout(loopResetTimer)
    loopResetTimer = null
  }
}

function goNext() {
  trackIndex.value += 1
  if (trackIndex.value >= slides.length) {
    clearLoopResetTimer()
    loopResetTimer = setTimeout(() => {
      isJumping.value = true
      trackIndex.value = 0
      requestAnimationFrame(() => {
        requestAnimationFrame(() => {
          isJumping.value = false
        })
      })
    }, SLIDE_TRANSITION_MS)
  }
}

function goPrev() {
  if (trackIndex.value > 0) {
    trackIndex.value -= 1
  }
}

function stopAutoSlide() {
  if (autoSlideTimer) {
    clearInterval(autoSlideTimer)
    autoSlideTimer = null
  }
}

function startAutoSlide() {
  stopAutoSlide()
  if (reducedMotionQuery.matches) return
  autoSlideTimer = setInterval(goNext, AUTO_SLIDE_INTERVAL_MS)
}

function handleMotionPreferenceChange() {
  startAutoSlide()
}

function onPointerDown(event) {
  isDragging.value = true
  pointerStartX = event.clientX
  event.currentTarget.setPointerCapture(event.pointerId)
}

function onPointerMove(event) {
  if (!isDragging.value) return
  dragOffset.value = event.clientX - pointerStartX
}

function onPointerUp() {
  if (!isDragging.value) return
  isDragging.value = false

  if (dragOffset.value <= -SWIPE_THRESHOLD) {
    goNext()
  } else if (dragOffset.value >= SWIPE_THRESHOLD) {
    goPrev()
  }
  dragOffset.value = 0

  // 사용자가 직접 넘기면 자동 전환 타이머를 리셋해서 조작 직후 바로 넘어가지 않게 한다.
  startAutoSlide()
}

const trackStyle = computed(() => ({
  transform: `translateX(${-(trackIndex.value * SLIDE_STEP) + dragOffset.value}px)`,
  transition: isDragging.value || isJumping.value ? 'none' : 'transform 0.3s ease',
}))

onMounted(() => {
  startAutoSlide()
  reducedMotionQuery.addEventListener('change', handleMotionPreferenceChange)
})

onUnmounted(() => {
  stopAutoSlide()
  clearLoopResetTimer()
  reducedMotionQuery.removeEventListener('change', handleMotionPreferenceChange)
})
</script>

<template>
  <div class="landing">
    <header class="landing-header">
      <div class="brand">
        <div class="brand-mark">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path
              d="M2.5 18V10.25A6.5 6.5 0 0 1 15.5 10.25V14H20A2 2 0 0 1 20 18Z"
              fill="#FFFFFF"
            />
          </svg>
        </div>
        <span class="brand-name">노카</span>
      </div>
      <button type="button" class="login-link" @click="goToLogin">로그인</button>
    </header>

    <section class="carousel-section">
      <div
        class="carousel-viewport"
        @pointerdown="onPointerDown"
        @pointermove="onPointerMove"
        @pointerup="onPointerUp"
        @pointercancel="onPointerUp"
      >
        <div class="carousel-track" :style="trackStyle">
          <div v-for="slide in loopedSlides" :key="slide.id" class="slide">
            <img :src="slide.image" :alt="slide.alt" draggable="false" class="slide-image" />
            <div
              v-for="(box, boxIndex) in slide.boxes"
              :key="boxIndex"
              class="roi-box"
              :style="{
                top: box.top + '%',
                left: box.left + '%',
                width: box.width + '%',
                height: box.height + '%',
              }"
            >
              <span class="roi-label" :class="`roi-label--${box.labelPosition || 'above'}`">{{
                box.label
              }}</span>
            </div>
          </div>
        </div>
      </div>

      <div class="carousel-dots">
        <span
          v-for="(slide, index) in slides"
          :key="slide.id"
          class="dot"
          :class="{ 'dot--active': index === activeIndex }"
        ></span>
      </div>
    </section>

    <section class="headline-section">
      <h1 class="headline">정비소 가기 전에,<br />수리비부터 알고 가세요</h1>
      <p class="subcopy">사진 한 장이면 손상 부위와 예상 수리비를 확인할 수 있어요.</p>
    </section>

    <section class="cta-section">
      <BaseButton @click="goToLogin">사진으로 견적 확인</BaseButton>
    </section>

    <section class="steps-section">
      <h2 class="steps-title">3단계면 끝나요</h2>
      <p class="steps-subcopy">사진만 찍으면 나머지는 저희가 합니다</p>

      <div class="steps">
        <template v-for="(step, index) in steps" :key="step.title">
          <div class="step">
            <div class="step-number">{{ index + 1 }}</div>
            <div class="step-title">{{ step.title }}</div>
            <div class="step-desc">
              <span v-for="(line, lineIndex) in step.desc" :key="line">
                {{ line }}<br v-if="lineIndex < step.desc.length - 1" />
              </span>
            </div>
          </div>
          <div v-if="index < steps.length - 1" class="step-arrow">›</div>
        </template>
      </div>
    </section>

    <footer class="landing-footer">
      <p>
        예상 견적은 사진 분석 기반의 참고용 정보이며, 실제 수리비는 정비소 점검 결과에 따라
        달라질 수 있습니다.
      </p>
    </footer>
  </div>
</template>

<style scoped>
.landing {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

.landing-header {
  flex: 0 0 56px;
  height: 56px;
  padding: 0 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.brand {
  display: flex;
  align-items: center;
  gap: 8px;
}

.brand-mark {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  background: var(--color-brand-500);
  display: flex;
  align-items: center;
  justify-content: center;
}

.brand-name {
  font-size: 20px;
  font-weight: 700;
  color: var(--color-text);
  letter-spacing: -0.03em;
}

.login-link {
  border: 0;
  background: none;
  padding: 0;
  font-family: inherit;
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text-secondary);
  cursor: pointer;
}

.carousel-section {
  padding: 16px 0 0 20px;
}

.carousel-viewport {
  width: 320px;
  overflow: hidden;
  touch-action: pan-y;
  cursor: grab;
}

.carousel-track {
  display: flex;
  gap: 12px;
  will-change: transform;
}

.slide {
  position: relative;
  flex: 0 0 300px;
  height: 200px;
  border-radius: var(--radius-card);
  overflow: hidden;
  background: var(--color-disabled-bg);
  border: 1px solid var(--color-border);
}

.slide-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  user-select: none;
}

.roi-box {
  position: absolute;
  border: 2px solid var(--color-brand-500);
  background: var(--color-brand-tint);
  border-radius: 4px;
}

.roi-label {
  position: absolute;
  left: -2px;
  white-space: nowrap;
  background: var(--color-brand-500);
  color: #ffffff;
  font-size: var(--font-size-xs);
  font-weight: 500;
  padding: 4px 8px;
  border-radius: 6px;
}

.roi-label--above {
  bottom: calc(100% + 6px);
}

.roi-label--below {
  top: calc(100% + 6px);
}

.carousel-dots {
  width: 300px;
  display: flex;
  gap: 5px;
  justify-content: center;
  padding-top: 14px;
}

.dot {
  width: 16px;
  height: 4px;
  border-radius: 8px;
  background: var(--color-border);
  transition: background 0.3s;
}

.dot--active {
  background: var(--color-brand-500);
}

.headline-section {
  padding: 22px 20px 0;
}

.headline {
  margin: 0;
  font-size: 24px;
  line-height: 1.35;
  font-weight: 800;
  color: var(--color-text);
  letter-spacing: -0.035em;
}

.subcopy {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--color-text-sub);
}

.cta-section {
  padding: 18px 20px 0;
}

.steps-section {
  padding: 26px 20px 0;
}

.steps-title {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  color: var(--color-text);
  letter-spacing: -0.02em;
}

.steps-subcopy {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--color-text-sub);
}

.steps {
  margin-top: 16px;
  padding: 16px 12px;
  background: var(--color-bg-subtle);
  border-radius: var(--radius-card);
  display: grid;
  grid-template-columns: 1fr 12px 1fr 12px 1fr;
  align-items: start;
}

.step {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.step-number {
  width: 40px;
  height: 40px;
  border-radius: 20px;
  background: var(--color-brand-50);
  color: var(--color-brand-700);
  font-size: 15px;
  font-weight: 800;
  display: flex;
  align-items: center;
  justify-content: center;
}

.step-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--color-text);
}

.step-desc {
  font-size: 11px;
  color: var(--color-text-sub);
  text-align: center;
  line-height: 1.4;
}

.step-arrow {
  align-self: center;
  margin-top: -22px;
  color: var(--color-divider-strong);
  font-size: 13px;
  text-align: center;
}

.landing-footer {
  margin-top: auto;
  padding: 14px 20px 18px;
  border-top: 1px solid var(--color-border);
}

.landing-footer p {
  margin: 0;
  font-size: 11px;
  line-height: 1.5;
  color: var(--color-text-sub);
}
</style>
