// 차량 관련 상수 — 서버가 내려주지 않는 표시용 값. 출처: Docs/Handover/차량 API — FE 인수인계.md §3 (그대로 복사해 쓸 것)

// 3-1. 제조사 → 5그룹. 배열 순서가 곧 드롭다운 표시 순서다. 서버는 브랜드 문자열 12종만 준다.
// ⚠️ 철자 주의: '벤츠'(메르세데스-벤츠 아님) · 'KG모빌리티' · '르노코리아'(공백 없음)
export const MANUFACTURER_GROUPS = [
  { group: '현대', brands: ['현대'] },
  { group: '기아', brands: ['기아'] },
  { group: '제네시스', brands: ['제네시스'] },
  { group: '기타 국산', brands: ['KG모빌리티', '르노코리아', '쉐보레'] },
  { group: '수입', brands: ['BMW', '벤츠', '아우디', '도요타', '폭스바겐', '혼다'] },
]

// 3-2. 차급 코드 → 한글. 4단계가 전부다 — "준중형"은 이 체계에 없다. 아반떼는 Mid-size(중형)이며 버그가 아니다.
export const CAR_CLASS_LABEL = { 'CityCar': '경형', 'Compact': '소형', 'Mid-size': '중형', 'Full-size': '대형' }

// 3-3. 차량 유형 코드 → 한글 (Story 표기 그대로)
export const VEHICLE_TYPE_LABEL = { SEDAN: '승용', SUV: 'SUV', VAN: '승합', TRUCK: '화물' }

// 3-5. 분석 미지원 차종 — 학습 데이터셋에서 제외된 승합·화물. 하드코딩 대신 vehicleType 으로 판별한다
export const isUnsupportedVehicle = (v) => v?.vehicleType === 'VAN' || v?.vehicleType === 'TRUCK'

// 1-2. 연식 허용 범위 (경계 포함)
export const MODEL_YEAR_MIN = 1980
export const MODEL_YEAR_MAX = 2100

export const carClassLabel = (code) => CAR_CLASS_LABEL[code] || code || ''
export const vehicleTypeLabel = (code) => VEHICLE_TYPE_LABEL[code] || code || ''

/** "현대 아반떼" */
export const vehicleName = (v) => [v?.manufacturer, v?.modelName].filter(Boolean).join(' ')

/** "승용 · 중형" */
export const vehicleSpec = (v) => [vehicleTypeLabel(v?.vehicleType), carClassLabel(v?.carClass)].filter(Boolean).join(' · ')

/**
 * 3-4. 제조사를 5그룹 순서로 묶는다. 서버 응답 순서는 collation 문제로 의미가 없어 FE 가 정렬한다.
 * 표에 없는 브랜드는 임의 그룹에 넣지 않고 콘솔 경고와 함께 '기타' 로 눈에 띄게 뒤에 붙인다.
 */
export function groupManufacturers(models) {
  const present = new Set(models.map((m) => m.manufacturer))
  const groups = MANUFACTURER_GROUPS
    .map((g) => ({ group: g.group, brands: g.brands.filter((b) => present.has(b)) }))
    .filter((g) => g.brands.length)
  const known = new Set(MANUFACTURER_GROUPS.flatMap((g) => g.brands))
  const unknown = [...present].filter((b) => !known.has(b)).sort((a, b) => a.localeCompare(b, 'ko'))
  if (unknown.length) {
    console.warn('[vehicles] 그룹 표에 없는 제조사:', unknown)
    groups.push({ group: '기타', brands: unknown })
  }
  return groups
}

/** 3-4. 모델명 가나다순 — 서버 정렬이 글자 수 순이라 FE 가 정렬한다 (서버 collation 수정 뒤 제거 가능) */
export const sortModels = (models) => [...models].sort((a, b) => a.modelName.localeCompare(b.modelName, 'ko'))
