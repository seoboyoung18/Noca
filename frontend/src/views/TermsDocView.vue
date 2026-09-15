<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import Screen from '../components/Screen.vue'
import AppHeader from '../components/AppHeader.vue'
import { termsDoc } from '../data/terms'

// doc: 'service' | 'privacy' — 라우트마다 고정값으로 넘긴다 (이용약관 · 개인정보 처리방침 화면을 각각 둠)
const props = defineProps({ doc: { type: String, required: true } })

const router = useRouter()
const d = computed(() => termsDoc(props.doc))

// 약관 동의 화면(가입)과 마이페이지 메뉴 두 곳에서 들어오므로 온 곳으로 되돌아간다.
// 주소를 직접 열어 이전 기록이 없으면 메뉴 화면으로 보낸다.
function back() {
  if (window.history.length > 1) router.back()
  else router.replace('/terms/docs')
}
</script>

<template>
  <Screen>
    <AppHeader :title="d?.title || '약관'" line>
      <template #left>
        <button class="back" aria-label="뒤로가기" @click="back">
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M12.5 3.5L6 10l6.5 6.5" stroke="#191F28" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </button>
      </template>
    </AppHeader>

    <div v-if="d" class="body scroll doc">
      <p class="meta">시행일 {{ d.effective }} · v{{ d.version }}</p>
      <p v-if="d.intro" class="intro">{{ d.intro }}</p>

      <section v-for="(s, i) in d.sections" :key="i">
        <h2>{{ s.heading }}</h2>
        <template v-for="(p, j) in s.body" :key="j">
          <!-- 표: { table: { head, rows } } -->
          <div v-if="typeof p === 'object' && p.table" class="tbl-wrap">
            <table>
              <thead><tr><th v-for="(h, k) in p.table.head" :key="k">{{ h }}</th></tr></thead>
              <tbody>
                <tr v-for="(row, r) in p.table.rows" :key="r"><td v-for="(c, k) in row" :key="k">{{ c }}</td></tr>
              </tbody>
            </table>
          </div>
          <!-- 글머리 목록: "- " 로 시작하는 문자열 -->
          <ul v-else-if="typeof p === 'string' && p.startsWith('- ')"><li>{{ p.slice(2) }}</li></ul>
          <p v-else>{{ p }}</p>
        </template>
      </section>
      <div style="height:32px"></div>
    </div>

    <div v-else class="body col" style="justify-content:center;align-items:center">
      <p class="sub">문서를 찾을 수 없어요</p>
    </div>
  </Screen>
</template>

<style scoped>
.doc { padding-top: 16px; }
.meta { font-size: 12px; color: var(--text-3); }
.intro { margin-top: 16px; font-size: 14px; line-height: 1.7; color: var(--text-2); }
section { margin-top: 24px; }
.tbl-wrap { margin-top: 10px; overflow-x: auto; border: 1px solid var(--line); border-radius: 10px; }
table { width: 100%; border-collapse: collapse; font-size: 13px; line-height: 1.5; }
th, td { padding: 8px 10px; text-align: left; vertical-align: top; border-bottom: 1px solid var(--line); }
th { background: var(--bg); font-weight: 600; color: var(--text); white-space: nowrap; }
td { color: var(--text-2); }
tr:last-child td { border-bottom: 0; }
td:first-child { color: var(--text); font-weight: 500; white-space: nowrap; }
/* 표 뒤에 이어지는 설명 문단과 연속된 목록 항목의 간격 */
ul + ul { margin-top: 2px; }
section h2 { font-size: 15px; font-weight: 700; color: var(--text); letter-spacing: -0.02em; }
section p { margin-top: 8px; font-size: 14px; line-height: 1.7; color: var(--text-2); white-space: pre-line; }
section ul { margin-top: 8px; padding-left: 18px; }
section li { font-size: 14px; line-height: 1.7; color: var(--text-2); }
section li + li { margin-top: 2px; }
</style>
