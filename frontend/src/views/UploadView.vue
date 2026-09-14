<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import { useAppStore } from '../stores/app'

const router = useRouter()
const store = useAppStore()
const total = computed(() => store.uploads.length)
const done = computed(() => store.uploadedCount)
const remain = computed(() => total.value - done.value)
</script>

<template>
  <Screen>
    <AppHeader title="사진 업로드" back="/claim/guide" />
    <div class="prog"><i style="width:75%"></i></div>

    <div class="body" style="padding-top:20px">
      <p class="step">3 / 4 · 사진 업로드</p>
      <h1 class="h1 sm" style="margin-top:6px">{{ total }}장을 모두 올려주세요</h1>

      <div class="grid">
        <button v-for="u in store.uploads" :key="u.key" class="slot" :class="u.state" @click="store.fillSlot(u.key)" :aria-label="u.label + ' 사진'">
          <template v-if="u.state === 'done'">
            <img v-if="u.key === 'front'" src="/assets/avante-damage.png" alt="">
            <span class="ok">
              <svg width="13" height="13" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M3 8.6L6.4 12L13 4.6" stroke="#FFFFFF" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </span>
            <span class="cap">{{ u.label }}</span>
          </template>
          <template v-else-if="u.state === 'error'">
            <span class="dim"></span>
            <svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true" style="position:relative"><circle cx="10" cy="10" r="8.2" stroke="#D93F45" stroke-width="1.8"/><path d="M10 5.8v5" stroke="#D93F45" stroke-width="1.8" stroke-linecap="round"/><circle cx="10" cy="13.8" r="1" fill="#D93F45"/></svg>
            <span class="retry">다시 찍기</span>
            <span class="cap">{{ u.label }}</span>
          </template>
          <template v-else>
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 5.5v13M5.5 12h13" stroke="#8B95A1" stroke-width="1.8" stroke-linecap="round"/></svg>
            <span class="lbl2">{{ u.label }}</span>
          </template>
        </button>
      </div>

      <p class="sub" style="margin-top:16px;font-size:12px">흐리거나 어두운 사진은 다시 찍어주세요</p>
      <div style="margin-top:12px">
        <button class="link" @click="router.push('/claim/guide')">촬영 가이드 다시 보기</button>
      </div>
      <div style="height:16px"></div>
    </div>

    <div class="foot">
      <div class="row between">
        <span class="sub">{{ remain > 0 ? remain + '장 남았어요' : '모두 올렸어요' }}</span>
        <span style="font-size:13px;font-weight:600">{{ done }} / {{ total }}</span>
      </div>
      <div class="bar"><i :style="{ width: (done / total) * 100 + '%' }"></i></div>
      <button class="btn" style="margin-top:14px" :disabled="done < total" @click="router.push('/claim/analyzing')">분석 요청</button>
    </div>
  </Screen>
</template>

<style scoped>
.grid { margin-top: 20px; display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.slot { position: relative; width: 100%; aspect-ratio: 1; border-radius: 12px; overflow: hidden; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px; background: var(--bg); background-image: repeating-linear-gradient(135deg, var(--bg) 0 8px, var(--stripe) 8px 16px); }
.slot img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
.slot.empty { background: var(--bg-2); background-image: none; border: 1.5px dashed var(--line-2); }
.slot.empty:hover { background: var(--bg); }
.slot.error { border: 1.5px solid var(--danger); }
.dim { position: absolute; inset: 0; background: rgba(217,63,69,.15); }
.retry { position: relative; font-size: 12px; font-weight: 500; color: var(--danger); }
.lbl2 { font-size: 13px; color: var(--text-3); }
.ok { position: absolute; right: 8px; top: 8px; width: 24px; height: 24px; border-radius: 12px; background: var(--primary); display: flex; align-items: center; justify-content: center; }
.cap { position: absolute; left: 8px; bottom: 8px; background: rgba(25,31,40,.7); color: #fff; font-size: 11px; padding: 3px 8px; border-radius: 4px; }
.bar { margin-top: 10px; height: 4px; border-radius: 2px; background: var(--line); overflow: hidden; }
.bar i { display: block; height: 100%; border-radius: 2px; background: var(--primary); transition: width .3s; }
</style>
