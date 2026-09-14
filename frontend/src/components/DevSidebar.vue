<script setup>
import { useRoute, useRouter } from 'vue-router'
import { screens } from '../router'
import { useAppStore } from '../stores/app'

const route = useRoute()
const router = useRouter()
const store = useAppStore()
const groups = [...new Set(screens.map((s) => s.meta.group))]

function setHome(mode) {
  store.homeMode = mode
  router.push('/home')
}
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
        <div v-if="s.path === '/home'" class="st8">
          <span>홈 상태</span>
          <button :class="{ on: store.homeMode === 'busy' }" @click="setHome('busy')">진행 중</button>
          <button :class="{ on: store.homeMode === 'idle' }" @click="setHome('idle')">기본</button>
          <button :class="{ on: store.homeMode === 'empty' }" @click="setHome('empty')">빈 상태</button>
        </div>
      </template>
    </template>
  </aside>
</template>
