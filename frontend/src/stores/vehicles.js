import { defineStore } from 'pinia'
import { createVehicle, deleteVehicle, fetchMyVehicles, fetchVehicleModels, updateVehicleYear } from '../lib/api'
import { AUTH_GUARD_OFF } from '../router'

// 서버 차량 모양으로 맞춘 프로토타입 목업 (VITE_AUTH_GUARD=off 로 백엔드 없이 화면을 볼 때만)
const MOCK_VEHICLES = [
  { vehicleId: 1, modelId: 14, manufacturer: '현대', modelName: '아반떼', vehicleType: 'SEDAN', carClass: 'Mid-size', modelYear: 2021 },
  { vehicleId: 2, modelId: 44, manufacturer: '기아', modelName: '쏘렌토', vehicleType: 'SUV', carClass: 'Full-size', modelYear: 2019 },
]
const MOCK_MODELS = [
  { modelId: 14, manufacturer: '현대', modelName: '아반떼', vehicleType: 'SEDAN', carClass: 'Mid-size' },
  { modelId: 15, manufacturer: '현대', modelName: '쏘나타', vehicleType: 'SEDAN', carClass: 'Mid-size' },
  { modelId: 44, manufacturer: '기아', modelName: '쏘렌토', vehicleType: 'SUV', carClass: 'Full-size' },
  { modelId: 46, manufacturer: '기아', modelName: 'K5', vehicleType: 'SEDAN', carClass: 'Mid-size' },
]

/**
 * 내 차량 · 차량 모델 마스터.
 * 서버 응답을 그대로 담는다 — { vehicleId, modelId, manufacturer, modelName, vehicleType, carClass, modelYear }.
 * 모델 목록은 51종 전체가 한 번에 오고 세션 중 바뀌지 않으므로 한 번만 받는다.
 */
export const useVehicleStore = defineStore('vehicles', {
  state: () => ({
    vehicles: [],
    vehiclesLoaded: false,
    models: [],
    modelsLoaded: false,
    // 사고 접수(S04)에서 고른 차량. 목록에 없는 id 면 첫 차량으로 보정된다
    selectedVehicleId: null,
  }),

  getters: {
    selected: (s) => s.vehicles.find((v) => v.vehicleId === s.selectedVehicleId) || null,
  },

  actions: {
    async loadVehicles(force = false) {
      if (this.vehiclesLoaded && !force) return this.vehicles
      if (AUTH_GUARD_OFF) { this.vehicles = [...MOCK_VEHICLES]; this.vehiclesLoaded = true; this.ensureSelection(); return this.vehicles }
      this.vehicles = await fetchMyVehicles()
      this.vehiclesLoaded = true
      this.ensureSelection()
      return this.vehicles
    },

    async loadModels() {
      if (this.modelsLoaded) return this.models
      this.models = AUTH_GUARD_OFF ? [...MOCK_MODELS] : await fetchVehicleModels()
      this.modelsLoaded = true
      return this.models
    },

    /** 등록. 서버가 201 로 완성된 차량을 주므로 목록 맨 앞(created_at DESC)에 넣고 재조회하지 않는다 */
    async add(modelId, modelYear) {
      let created
      if (AUTH_GUARD_OFF) {
        const m = this.models.find((x) => x.modelId === modelId) || {}
        created = { vehicleId: Date.now(), modelId, manufacturer: m.manufacturer, modelName: m.modelName, vehicleType: m.vehicleType, carClass: m.carClass, modelYear }
      } else {
        created = await createVehicle(modelId, modelYear)
      }
      this.vehicles = [created, ...this.vehicles]
      this.vehiclesLoaded = true
      this.selectedVehicleId = created.vehicleId
      return created
    },

    async changeYear(vehicleId, modelYear) {
      let updated
      if (AUTH_GUARD_OFF) updated = { ...this.vehicles.find((v) => v.vehicleId === vehicleId), modelYear }
      else updated = await updateVehicleYear(vehicleId, modelYear)
      this.vehicles = this.vehicles.map((v) => (v.vehicleId === vehicleId ? { ...v, ...updated } : v))
      return updated
    },

    /** 삭제(소프트). 사고 이력은 남는다. 이미 지운 차량도 204 라 중복 클릭에 안전 */
    async remove(vehicleId) {
      if (!AUTH_GUARD_OFF) await deleteVehicle(vehicleId)
      this.vehicles = this.vehicles.filter((v) => v.vehicleId !== vehicleId)
      this.ensureSelection()
    },

    ensureSelection() {
      if (!this.vehicles.some((v) => v.vehicleId === this.selectedVehicleId)) {
        this.selectedVehicleId = this.vehicles[0]?.vehicleId ?? null
      }
    },

    /** 로그아웃·탈퇴 시 */
    reset() { this.vehicles = []; this.vehiclesLoaded = false; this.selectedVehicleId = null },
  },
})
