<script setup>
import { ref } from 'vue'
/* PC 기기 목업 스위치 — 기본 켬. localStorage 는 사생활 모드 등에서 막힐 수 있어 읽고 쓸 때 모두 감싼다 */
const MOCK_KEY = 'noka.deviceMock'
const deviceMock = ref((() => { try { return localStorage.getItem(MOCK_KEY) !== 'off' } catch { return true } })())
function toggleDeviceMock() { deviceMock.value = !deviceMock.value; try { localStorage.setItem(MOCK_KEY, deviceMock.value ? 'on' : 'off') } catch {} }
</script>

<template>
  <!-- PC 에서는 갤럭시 느낌의 기기 목업(.device: 평면 베젤 + 펀치홀 카메라 + 볼륨·전원 키) 안에 폰 화면(360x800)을,
       모바일에서는 목업 없이 전체 화면 (styles.css 의 .shell/.stage/.device/.phone) -->
  <div class="shell">
    <main class="stage">
      <div class="device" :class="{ plain: !deviceMock }">
        <div class="phone">
          <router-view />
        </div>
        <i class="cam" aria-hidden="true"></i>
        <i class="key vol" aria-hidden="true"></i>
        <i class="key pwr" aria-hidden="true"></i>
      </div>
    </main>
    <!-- PC 전용: 기기 목업 켜기/끄기 스위치 — 화면 바깥 오른쪽 위. 선택은 이 브라우저에만 저장된다 -->
    <button type="button" class="mock-sw" :class="{ on: deviceMock }" role="switch" :aria-checked="deviceMock" @click="toggleDeviceMock">
      <span class="knob" aria-hidden="true"></span>
      <span class="lbl">스마트폰 목업</span>
    </button>
  </div>
</template>
