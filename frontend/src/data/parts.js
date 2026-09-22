// 부품 마스터 중 AI 가 사진에서 검출하는 32종 (part_code.code_scope = AI_LABEL). 서버 마스터(2026-09-10-admin-master-and-rules.sql)와 같은 코드·이름·순서.
// 회원용 부품 목록 API 가 없어 FE 에 둔다 — 부품 미확정(PART_NOT_RESOLVED) 견적에서 사용자가 부위를 직접 고를 때 쓴다.
// 서버가 응답에 실어 주는 부품명(partNameKo)은 그대로 쓰고, 이 표는 "고르는 목록" 에만 쓴다.
export const PART_ZONES = [
  { code: 'FRONT', text: '앞' },
  { code: 'REAR', text: '뒤' },
  { code: 'SIDE_L', text: '왼쪽' },
  { code: 'SIDE_R', text: '오른쪽' },
  { code: 'TOP', text: '위' },
  { code: 'UNDER', text: '아래' },
]

/** 부품 카테고리 — 고르는 화면이 위치 안에서 다시 묶는 단위. 순서대로 보인다 */
export const PART_CATEGORIES = [
  { code: 'PANEL', text: '범퍼·패널' },
  { code: 'DOOR', text: '도어·미러' },
  { code: 'GLASS', text: '유리·필러' },
  { code: 'LAMP', text: '램프' },
  { code: 'WHEEL', text: '휠·하부' },
]
const CATEGORY_OF = {
  FRONT_BUMPER: 'PANEL', REAR_BUMPER: 'PANEL', BONNET: 'PANEL', TRUNK_LID: 'PANEL', ROOF: 'PANEL',
  FRONT_FENDER_L: 'PANEL', FRONT_FENDER_R: 'PANEL', REAR_FENDER_L: 'PANEL', REAR_FENDER_R: 'PANEL', ROCKER_PANEL_L: 'PANEL', ROCKER_PANEL_R: 'PANEL',
  FRONT_DOOR_L: 'DOOR', FRONT_DOOR_R: 'DOOR', REAR_DOOR_L: 'DOOR', REAR_DOOR_R: 'DOOR', SIDE_MIRROR_L: 'DOOR', SIDE_MIRROR_R: 'DOOR',
  WINDSHIELD: 'GLASS', REAR_WINDSHIELD: 'GLASS', A_PILLAR_L: 'GLASS', A_PILLAR_R: 'GLASS', C_PILLAR_L: 'GLASS', C_PILLAR_R: 'GLASS',
  HEAD_LIGHT_L: 'LAMP', HEAD_LIGHT_R: 'LAMP', REAR_LAMP_L: 'LAMP', REAR_LAMP_R: 'LAMP',
  FRONT_WHEEL_L: 'WHEEL', FRONT_WHEEL_R: 'WHEEL', REAR_WHEEL_L: 'WHEEL', REAR_WHEEL_R: 'WHEEL', UNDERCARRIAGE: 'WHEEL',
}

const RAW_PARTS = [
  { code: 'FRONT_BUMPER', name: '앞 범퍼', zone: 'FRONT' },
  { code: 'REAR_BUMPER', name: '뒤 범퍼', zone: 'REAR' },
  { code: 'BONNET', name: '보닛', zone: 'FRONT' },
  { code: 'TRUNK_LID', name: '트렁크', zone: 'REAR' },
  { code: 'ROOF', name: '루프', zone: 'TOP' },
  { code: 'WINDSHIELD', name: '앞 유리', zone: 'FRONT' },
  { code: 'REAR_WINDSHIELD', name: '뒤 유리', zone: 'REAR' },
  { code: 'UNDERCARRIAGE', name: '차량 하부', zone: 'UNDER' },
  { code: 'A_PILLAR_L', name: 'A 필러(좌)', zone: 'SIDE_L' },
  { code: 'A_PILLAR_R', name: 'A 필러(우)', zone: 'SIDE_R' },
  { code: 'C_PILLAR_L', name: 'C 필러(좌)', zone: 'SIDE_L' },
  { code: 'C_PILLAR_R', name: 'C 필러(우)', zone: 'SIDE_R' },
  { code: 'FRONT_DOOR_L', name: '앞 도어(좌)', zone: 'SIDE_L' },
  { code: 'FRONT_DOOR_R', name: '앞 도어(우)', zone: 'SIDE_R' },
  { code: 'REAR_DOOR_L', name: '뒤 도어(좌)', zone: 'SIDE_L' },
  { code: 'REAR_DOOR_R', name: '뒤 도어(우)', zone: 'SIDE_R' },
  { code: 'FRONT_FENDER_L', name: '앞 펜더(좌)', zone: 'SIDE_L' },
  { code: 'FRONT_FENDER_R', name: '앞 펜더(우)', zone: 'SIDE_R' },
  { code: 'REAR_FENDER_L', name: '뒤 펜더(좌)', zone: 'SIDE_L' },
  { code: 'REAR_FENDER_R', name: '뒤 펜더(우)', zone: 'SIDE_R' },
  { code: 'FRONT_WHEEL_L', name: '앞 휠(좌)', zone: 'SIDE_L' },
  { code: 'FRONT_WHEEL_R', name: '앞 휠(우)', zone: 'SIDE_R' },
  { code: 'REAR_WHEEL_L', name: '뒤 휠(좌)', zone: 'SIDE_L' },
  { code: 'REAR_WHEEL_R', name: '뒤 휠(우)', zone: 'SIDE_R' },
  { code: 'HEAD_LIGHT_L', name: '헤드램프(좌)', zone: 'SIDE_L' },
  { code: 'HEAD_LIGHT_R', name: '헤드램프(우)', zone: 'SIDE_R' },
  { code: 'REAR_LAMP_L', name: '리어램프(좌)', zone: 'SIDE_L' },
  { code: 'REAR_LAMP_R', name: '리어램프(우)', zone: 'SIDE_R' },
  { code: 'ROCKER_PANEL_L', name: '로커 패널(좌)', zone: 'SIDE_L' },
  { code: 'ROCKER_PANEL_R', name: '로커 패널(우)', zone: 'SIDE_R' },
  { code: 'SIDE_MIRROR_L', name: '사이드미러(좌)', zone: 'SIDE_L' },
  { code: 'SIDE_MIRROR_R', name: '사이드미러(우)', zone: 'SIDE_R' },
]
export const AI_PARTS = RAW_PARTS.map((p) => ({ ...p, category: CATEGORY_OF[p.code] || 'PANEL' }))

/** 한 위치의 부품을 카테고리로 묶는다 — 비어 있는 카테고리는 뺀다. 고르는 화면의 "2. 부품" 이 카테고리마다 가로 스크롤 줄을 그린다 */
export function partsByCategory(zoneCode) {
  const inZone = AI_PARTS.filter((p) => p.zone === zoneCode)
  return PART_CATEGORIES.map((c) => ({ ...c, parts: inZone.filter((p) => p.category === c.code) })).filter((c) => c.parts.length)
}

/** 구역별로 묶은 목록 — 고르는 화면이 구역 머리글 아래 부품을 늘어놓는다. 비어 있는 구역은 뺀다 */
export const AI_PARTS_BY_ZONE = PART_ZONES
  .map((z) => ({ ...z, parts: AI_PARTS.filter((p) => p.zone === z.code) }))
  .filter((z) => z.parts.length)

export const partName = (code) => AI_PARTS.find((p) => p.code === code)?.name || code

/* ----- 사용자가 직접 고른 부위 — 서버 API 가 생기기 전까지 브라우저에만 둔다 (S15P21A307-564) ----- */
const KEY = (accidentId) => `noka.resolvedPart.${accidentId}`
export function readResolvedPart(accidentId) { try { return localStorage.getItem(KEY(accidentId)) || '' } catch { return '' } }
export function writeResolvedPart(accidentId, code) { try { code ? localStorage.setItem(KEY(accidentId), code) : localStorage.removeItem(KEY(accidentId)) } catch { /* 저장소 없으면 화면 안에서만 */ } }
