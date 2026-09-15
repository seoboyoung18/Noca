<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import { useVehicleStore } from '../stores/vehicles'
import { isUnsupportedVehicle, vehicleName, vehicleSpec } from '../data/vehicles'

// 사고 접수 1/4 · 차량 선택 — 내 차량 목록(GET /api/vehicles/me)에서 고른다. 선택값은 차량 스토어에 남아 다음 단계가 쓴다
const router = useRouter()
const vs = useVehicleStore()

const state = ref('loading') // loading | idle | error
const loadError = ref('')
onMounted(load)
async function load() {
  state.value = 'loading'
  try { await vs.loadVehicles(); state.value = 'idle' }
  catch (e) { state.value = 'error'; loadError.value = e.status === 0 ? e.message : '차량 목록을 불러오지 못했어요.' }
}
</script>

<template>
  <Screen>
    <AppHeader title="사고 접수" back="/home" />
    <div class="prog"><i style="width:25%"></i></div>

    <div class="body col" style="padding-top:20px">
      <p class="step">1 / 4 · 차량 선택</p>
      <h1 class="h1" style="margin-top:6px">어떤 차량인가요?</h1>

      <div v-if="state === 'loading'" class="stack" style="margin-top:20px">
        <div v-for="i in 2" :key="i" class="card skel" style="height:76px"></div>
      </div>

      <div v-else-if="state === 'error'" class="flex1" style="display:flex;flex-direction:column">
        <div class="empty">
          <b>{{ loadError }}</b>
          <button class="btn outline" style="margin-top:20px;width:auto;padding:0 24px;height:44px" @click="load">다시 시도</button>
        </div>
      </div>

      <template v-else-if="vs.vehicles.length">
        <div class="stack" style="margin-top:20px">
          <button v-for="v in vs.vehicles" :key="v.vehicleId" class="vcard" :class="{ on: vs.selectedVehicleId === v.vehicleId }" @click="vs.selectedVehicleId = v.vehicleId">
            <span class="radio" :class="{ on: vs.selectedVehicleId === v.vehicleId }"></span>
            <span style="display:flex;flex-direction:column;gap:3px;text-align:left;min-width:0">
              <span class="row" style="gap:6px"><span class="sec">{{ vehicleName(v) }}</span><span v-if="isUnsupportedVehicle(v)" class="tag gray">분석 미지원</span></span>
              <span class="sub">{{ v.modelYear }}년식 · {{ vehicleSpec(v) }}</span>
            </span>
          </button>
        </div>
        <div class="center" style="margin-top:16px">
          <button class="link" style="font-size:14px" @click="router.push('/vehicles/new')">+ 다른 차량 등록</button>
        </div>
      </template>

      <div v-else class="flex1" style="display:flex;flex-direction:column">
        <div class="empty">
          <img src="/assets/logo-small.png" alt="">
          <b>등록된 차량이 없어요</b>
          <p>차량을 등록하면<br>더 정확한 견적을 받을 수 있어요</p>
        </div>
      </div>
    </div>

    <div class="foot">
      <button v-if="vs.vehicles.length" class="btn" :disabled="!vs.selectedVehicleId" @click="router.push('/claim/guide')">다음</button>
      <button v-else class="btn" :disabled="state === 'loading'" @click="router.push('/vehicles/new')">차량 등록하기</button>
    </div>
  </Screen>
</template>

<style scoped>
.vcard { width: 100%; min-height: 76px; padding: 12px 16px; border: 1px solid var(--line); border-radius: 12px; background: var(--white); display: flex; align-items: center; gap: 14px; transition: border-color .15s; }
.vcard.on { border: 1.5px solid var(--primary); }
.skel { background: var(--bg); border-color: transparent; animation: pulse 1.2s ease-in-out infinite; }
@keyframes pulse { 50% { opacity: .55; } }
</style>
