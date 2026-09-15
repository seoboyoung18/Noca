import { defineStore } from 'pinia'

let seq = 1
const item = (text, extra = {}) => ({ id: seq++, text, done: false, ...extra })

function defaultChecklist() {
  return {
    common: [
      item('견적서 서면 수령 · 항목별 금액 확인'),
      item('부품 등급(순정·OEM·재생·중고)·부품 번호 확인'),
      item('작업 전·후 사진 요청 · 교체 부품 실물 확인'),
      item('보증 기간·보증서 · 소요 기간·대차 여부'),
    ],
    parts: [
      {
        id: 'bumper', name: '프론트 범퍼', fix: '교환', sev: 3,
        items: [
          item('내부 브래킷·고정 클립 손상 확인', { done: true }),
          item('주차 센서 손상·재보정 여부', { done: true }),
          item('도장 색상 매칭(조색·블렌딩) 범위'),
          item('교환 vs 수리 판단 근거 설명 요청'),
        ],
      },
      {
        id: 'lamp', name: '헤드램프(좌)', fix: '교환', sev: 2,
        items: [
          item('내부 습기·결로 여부', { done: true }),
          item('광축 조정 포함 여부', { memo: '조정비 별도인지 물어보기' }),
        ],
      },
    ],
    hidden: [
      item('라디에이터·에어컨 콘덴서 누수·변형', { why: '전면 심각 파손 시 범퍼 뒤 냉각 부품 충격 가능' }),
      item('헤드램프 브래킷·범퍼 서포트 변형', { why: '헤드램프 교환 시 고정부 함께 확인' }),
      item('휠 얼라인먼트 측정', { why: '전면 충격으로 조향 축이 틀어질 수 있음' }),
    ],
  }
}

function defaultUploads() {
  return [
    { key: 'front', label: '정면', state: 'done' },
    { key: 'angle', label: '45도', state: 'done' },
    { key: 'left', label: '왼쪽', state: 'error' },
    { key: 'right', label: '오른쪽', state: 'empty' },
  ]
}

export const useAppStore = defineStore('app', {
  state: () => ({
    // 홈 상태: busy(진행 중 분석 있음) | idle(기본) | empty(사고 없음)
    homeMode: 'busy',
    hasAvatar: false,
    agreed: false,
    // 약관 동의 화면의 체크 상태. 전문 화면(/terms/service 등)을 보고 돌아와도 체크가 유지되도록 화면 밖에 둔다
    termsChecked: { SERVICE: false, PRIVACY: false },

    vehicles: [
      { id: 1, maker: '현대', name: '현대 아반떼', year: '2021년식', cls: '준중형 세단', recent: '9월 5일', primary: true, claims: 2 },
      { id: 2, maker: '기아', name: '기아 쏘렌토', year: '2019년식', cls: '중형 SUV', recent: '8월 21일', primary: false, claims: 1 },
    ],
    selectedVehicleId: 1,

    uploads: defaultUploads(),

    history: [
      { id: 1, group: '이번 주', car: '현대 아반떼', date: '9월 5일', status: 'done', amount: '124만원', photo: true },
      { id: 2, group: '8월', car: '기아 쏘렌토', date: '8월 23일', status: 'busy', amount: '' },
      { id: 3, group: '8월', car: '현대 아반떼', date: '8월 12일', status: 'fail', amount: '' },
    ],

    checklists: [
      { id: 1, date: '9/5', car: '현대 아반떼', type: '전면 추돌 · 범퍼·헤드램프·펜더', fix: '교환 2곳, 판금·도장 1곳 · 예상 124만원' },
      { id: 2, date: '8/21', car: '기아 쏘렌토', type: '측면 접촉 · 뒷문·리어 펜더', fix: '뒷문 도장, 리어 펜더 판금 · 예상 86만원' },
    ],
    checklistTitle: '현대 아반떼',
    checklist: defaultChecklist(),

    notifications: { analysis: true, checklist: true, marketing: false },

    shopConsent: null, // null | 'gps' | 'region' | 'map'
    shopSido: '서울특별시',
    shopRegion: '강남구', // 시·군·구 (시·도에 하위 구역이 없으면 빈 문자열)
    shopDong: null, // 읍·면·동 또는 시 아래 구 { label, addr, lat, lng } — 선택하지 않으면 null (시·군·구 전체)
    shopRadius: 2,
  }),

  getters: {
    lists: (s) => [s.checklist.common, ...s.checklist.parts.map((p) => p.items), s.checklist.hidden],
    counts: (s) => ({
      common: s.checklist.common.length,
      parts: s.checklist.parts.reduce((n, p) => n + p.items.length, 0),
      hidden: s.checklist.hidden.length,
    }),
    uploadedCount: (s) => s.uploads.filter((u) => u.state === 'done').length,
    historyGroups: (s) => {
      const groups = []
      for (const h of s.history) {
        let g = groups.find((x) => x.label === h.group)
        if (!g) { g = { label: h.group, items: [] }; groups.push(g) }
        g.items.push(h)
      }
      return groups
    },
  },

  actions: {
    // 체크리스트
    toggleItem(it) { it.done = !it.done },
    updateItem(it, { text, memo }) {
      if (text) it.text = text
      if (memo) it.memo = memo
      else delete it.memo
    },
    deleteItem(it) {
      for (const list of this.lists) {
        const i = list.indexOf(it)
        if (i > -1) { list.splice(i, 1); return }
      }
    },
    addItem(cat, text) {
      const it = item(text, { custom: true, cat })
      if (cat === 'parts') {
        let g = this.checklist.parts.find((p) => p.id === 'custom')
        if (!g) { g = { id: 'custom', name: '직접 추가', fix: '', sev: 0, items: [] }; this.checklist.parts.push(g) }
        g.items.push(it)
      } else {
        this.checklist[cat].push(it)
      }
    },
    setTitle(t) { this.checklistTitle = t },
    regenerate() {
      const custom = this.lists.flat().filter((it) => it.custom)
      this.checklist = defaultChecklist()
      custom.forEach((c) => this.addItem(c.cat, c.text))
    },

    // 차량
    addVehicle({ maker, model, year }) {
      const id = Date.now()
      this.vehicles.push({ id, maker, name: `${maker} ${model}`, year: `${year}년식`, cls: '준중형 세단', recent: '-', primary: this.vehicles.length === 0, claims: 0 })
      this.selectedVehicleId = id
      return id
    },
    setPrimaryVehicle(id) { this.vehicles.forEach((c) => { c.primary = c.id === id }) },
    deleteVehicle(id) {
      this.vehicles = this.vehicles.filter((c) => c.id !== id)
      if (this.selectedVehicleId === id) this.selectedVehicleId = this.vehicles[0]?.id ?? null
    },

    // 업로드
    fillSlot(key) {
      const u = this.uploads.find((x) => x.key === key)
      if (u) u.state = 'done'
    },
    resetUploads() { this.uploads = defaultUploads() },

    // 이력
    deleteHistory(id) { this.history = this.history.filter((h) => h.id !== id) },
  },
})
