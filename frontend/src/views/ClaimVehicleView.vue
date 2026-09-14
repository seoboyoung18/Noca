<script setup>
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import { useAppStore } from '../stores/app'

const router = useRouter()
const store = useAppStore()
</script>

<template>
  <Screen>
    <AppHeader title="사고 접수" back="/home" />
    <div class="prog"><i style="width:25%"></i></div>

    <div class="body col" style="padding-top:20px">
      <p class="step">1 / 4 · 차량 선택</p>
      <h1 class="h1" style="margin-top:6px">어떤 차량인가요?</h1>

      <template v-if="store.vehicles.length">
        <div class="stack" style="margin-top:20px">
          <button v-for="c in store.vehicles" :key="c.id" class="vcard" :class="{ on: store.selectedVehicleId === c.id }" @click="store.selectedVehicleId = c.id">
            <span class="radio" :class="{ on: store.selectedVehicleId === c.id }"></span>
            <span style="display:flex;flex-direction:column;gap:3px;text-align:left">
              <span class="sec">{{ c.name }}</span>
              <span class="sub">{{ c.year }}</span>
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
      <button v-if="store.vehicles.length" class="btn" @click="router.push('/claim/guide')">다음</button>
      <button v-else class="btn" @click="router.push('/vehicles/new')">차량 등록하기</button>
    </div>
  </Screen>
</template>

<style scoped>
.vcard { width: 100%; height: 76px; padding: 0 16px; border: 1px solid var(--line); border-radius: 12px; background: var(--white); display: flex; align-items: center; gap: 14px; transition: border-color .15s; }
.vcard.on { border: 1.5px solid var(--primary); }
</style>
