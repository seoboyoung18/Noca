<script setup>
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import BottomSheet from '../components/BottomSheet.vue'
import { useAppStore } from '../stores/app'

const router = useRouter()
const store = useAppStore()
const sh = reactive({ more: false, del: false })
const curId = ref(null)
const cur = computed(() => store.vehicles.find((c) => c.id === curId.value))

function openMore(c) { curId.value = c.id; sh.more = true }
function setPrimary() { store.setPrimaryVehicle(curId.value); sh.more = false }
function edit() { sh.more = false; router.push('/vehicles/new?from=my') }
function askDelete() { sh.more = false; sh.del = true }
function doDelete() { store.deleteVehicle(curId.value); sh.del = false }
</script>

<template>
  <Screen>
    <AppHeader title="차량 관리" back="/my" line>
      <template #right>
        <button class="act" @click="router.push('/vehicles/new?from=my')">+ 추가</button>
      </template>
    </AppHeader>

    <div class="body scroll" style="padding-top:20px">
      <div v-if="store.vehicles.length" class="stack">
        <div v-for="c in store.vehicles" :key="c.id" class="card" style="padding:16px">
          <div class="row between">
            <span class="row" style="gap:8px">
              <span style="font-size:16px;font-weight:600">{{ c.name }}</span>
              <span v-if="c.primary" class="tag">대표 차량</span>
            </span>
            <button class="more" aria-label="더보기" @click="openMore(c)">
              <svg width="18" height="18" viewBox="0 0 18 18" fill="none" aria-hidden="true"><circle cx="9" cy="4" r="1.3" fill="#8B95A1"/><circle cx="9" cy="9" r="1.3" fill="#8B95A1"/><circle cx="9" cy="14" r="1.3" fill="#8B95A1"/></svg>
            </button>
          </div>
          <div class="sub" style="margin-top:6px">{{ c.year }} · {{ c.cls }}</div>
          <div class="row between" style="margin-top:10px">
            <span class="sub" style="font-size:12px">최근 사고 접수</span>
            <span style="font-size:12px;font-weight:500;color:var(--text-2)">{{ c.recent }}</span>
          </div>
        </div>
      </div>
      <div v-else class="empty" style="margin-top:80px">
        <img src="/assets/logo-small.png" alt="">
        <b>등록된 차량이 없어요</b>
        <p>차량을 등록하면<br>더 정확한 견적을 받을 수 있어요</p>
        <button class="btn" style="margin-top:20px;width:auto;padding:0 24px;height:44px" @click="router.push('/vehicles/new?from=my')">차량 등록하기</button>
      </div>
      <p class="sub center" style="margin-top:16px;font-size:12px">차량을 삭제해도 사고 이력은 남아요</p>
    </div>
    <div class="spacer"></div>

    <BottomSheet v-model="sh.more">
      <p class="st">{{ cur?.name }}</p>
      <div class="mlist">
        <button class="mrow" @click="setPrimary"><span class="mi"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#4E36E4" stroke-width="1.7" stroke-linejoin="round" aria-hidden="true"><path d="M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1L3.2 9.5l6.1-.9z"/></svg></span><span class="ml">대표 차량으로 설정</span></button>
        <button class="mrow" @click="edit"><span class="mi"><svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M12.5 3.5l4 4L7 17H3v-4z" stroke="#4E36E4" stroke-width="1.6" stroke-linejoin="round"/><path d="M10.5 5.5l4 4" stroke="#4E36E4" stroke-width="1.6"/></svg></span><span class="ml">정보 수정</span></button>
        <button class="mrow del" @click="askDelete"><span class="mi"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#D14343" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 7h14M9 7V4h6v3M7 7l1 13h8l1-13"/></svg></span><span class="ml">삭제</span></button>
      </div>
      <div class="acts" style="margin-top:12px"><button class="btn outline" @click="sh.more = false">닫기</button></div>
    </BottomSheet>

    <BottomSheet v-model="sh.del">
      <h2 class="st">이 차량을 삭제할까요?</h2>
      <p class="sd"><b style="color:var(--text)">{{ cur?.name }}</b>이(가) 내 차량 목록에서 사라져요.<br>연결된 사고 이력 <b style="color:var(--text)">{{ cur?.claims }}건</b>은 그대로 유지됩니다.</p>
      <div class="acts">
        <button class="btn outline" @click="sh.del = false">취소</button>
        <button class="btn danger bold" @click="doDelete">삭제</button>
      </div>
    </BottomSheet>
  </Screen>
</template>

<style scoped>
.more { width: 32px; height: 32px; margin: -7px -7px -7px 0; display: flex; align-items: center; justify-content: center; border-radius: 16px; }
.more:hover { background: var(--bg); }
</style>
