<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import BottomSheet from '../components/BottomSheet.vue'
import { useAppStore } from '../stores/app'

const router = useRouter()
const route = useRoute()
const store = useAppStore()
const backTo = route.query.from === 'my' ? '/my/vehicles' : '/claim/vehicle'

const MAKERS = { 현대: ['아반떼', '쏘나타', '그랜저', '투싼', '싼타페'], 기아: ['K5', 'K8', '쏘렌토', '스포티지', '레이'], 제네시스: ['G70', 'G80', 'GV70', 'GV80'], 쉐보레: ['스파크', '말리부', '트레일블레이저'] }
const YEARS = Array.from({ length: 12 }, (_, i) => String(2026 - i))

const maker = ref('현대')
const model = ref('아반떼')
const year = ref('')
const sheet = ref(null) // 'maker' | 'model' | 'year'

const models = computed(() => MAKERS[maker.value] || [])
const options = computed(() => sheet.value === 'maker' ? Object.keys(MAKERS) : sheet.value === 'model' ? models.value : YEARS)
const sheetTitle = computed(() => ({ maker: '제조사', model: '차량명', year: '연식' })[sheet.value] || '')
const current = computed(() => ({ maker: maker.value, model: model.value, year: year.value })[sheet.value])
const valid = computed(() => maker.value && model.value && year.value)

function pick(v) {
  if (sheet.value === 'maker') { maker.value = v; model.value = '' }
  else if (sheet.value === 'model') model.value = v
  else year.value = v
  sheet.value = null
}
function submit() {
  if (!valid.value) return
  store.addVehicle({ maker: maker.value, model: model.value, year: year.value })
  router.push(backTo)
}
</script>

<template>
  <Screen>
    <AppHeader title="차량 등록" :back="backTo" line />
    <div class="body" style="padding-top:28px;display:flex;flex-direction:column;gap:20px">
      <div class="field">
        <span class="fl">제조사</span>
        <button class="sel" @click="sheet = 'maker'"><span class="v">{{ maker }}</span><ChevDown /></button>
      </div>
      <div class="field">
        <span class="fl">차량명</span>
        <button class="sel" @click="sheet = 'model'"><span class="v" :class="{ ph: !model }">{{ model || '선택하세요' }}</span><ChevDown /></button>
        <p class="sub" style="margin-top:8px;font-size:12px">승용 · 준중형 — 자동으로 설정됩니다</p>
      </div>
      <div class="field">
        <span class="fl">연식</span>
        <button class="sel" @click="sheet = 'year'"><span class="v" :class="{ ph: !year }">{{ year ? year + '년' : '선택하세요' }}</span><ChevDown /></button>
      </div>
      <div class="center" style="margin-top:4px">
        <button class="link mute" style="font-size:14px;text-decoration:underline">목록에 없어요</button>
      </div>
    </div>
    <div class="foot">
      <button class="btn" :disabled="!valid" @click="submit">등록</button>
    </div>

    <BottomSheet :model-value="!!sheet" @update:model-value="sheet = null">
      <p class="st">{{ sheetTitle }}</p>
      <div class="opts scroll">
        <button v-for="o in options" :key="o" class="opt" :class="{ on: o === current }" @click="pick(o)">
          <span class="flex1">{{ o }}{{ sheet === 'year' ? '년' : '' }}</span>
          <svg v-if="o === current" width="18" height="18" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M3 8.6L6.4 12L13 4.6" stroke="#4E36E4" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </button>
      </div>
    </BottomSheet>
  </Screen>
</template>

<script>
import { h } from 'vue'
const ChevDown = () => h('svg', { width: 20, height: 20, viewBox: '0 0 20 20', fill: 'none', 'aria-hidden': 'true' }, [
  h('path', { d: 'M5.5 8L10 12.5L14.5 8', stroke: '#8B95A1', 'stroke-width': '1.7', 'stroke-linecap': 'round', 'stroke-linejoin': 'round' }),
])
export default { components: { ChevDown } }
</script>

<style scoped>
.opts { margin-top: 12px; max-height: 320px; overflow-y: auto; border: 1px solid var(--line); border-radius: 12px; }
.opt { width: 100%; height: 48px; padding: 0 16px; display: flex; align-items: center; font-size: 15px; color: var(--text); border-top: 1px solid var(--line); text-align: left; }
.opt:first-child { border-top: 0; }
.opt.on { color: var(--primary); font-weight: 500; }
.opt:hover { background: var(--bg-2); }
</style>
