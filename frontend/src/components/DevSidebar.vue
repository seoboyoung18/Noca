<script setup>
import { useRoute, useRouter } from 'vue-router'
import { screens } from '../router'

const route = useRoute()
const router = useRouter()
const groups = [...new Set(screens.map((s) => s.meta.group))]
// 홈 상태 토글(진행 중·기본·빈 상태)은 홈이 사고 목록 API 로 상태를 정하게 되면서 제거했다
</script>

<template>
  <aside class="side">
    <h1>Noka Prototype</h1>
    <template v-for="g in groups" :key="g">
      <div class="grp">{{ g }}</div>
      <template v-for="s in screens.filter((x) => x.meta.group === g)" :key="s.path">
        <button class="pc" :aria-current="String(route.path === s.path)" @click="router.push(s.path)">
          <small>{{ s.meta.code }}</small>{{ s.meta.name }}
        </button>
      </template>
    </template>
  </aside>
</template>
