// 카카오 지도 JavaScript SDK 동적 로더 (services 라이브러리 포함)
let pending = null

export const KAKAO_KEY = import.meta.env.VITE_KAKAO_JS_KEY || ''

export function loadKakao() {
  if (window.kakao?.maps?.services) return Promise.resolve(window.kakao)
  if (pending) return pending
  if (!KAKAO_KEY) return Promise.reject(new Error('NO_KEY'))

  pending = new Promise((resolve, reject) => {
    const s = document.createElement('script')
    s.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${KAKAO_KEY}&libraries=services&autoload=false`
    s.async = true
    s.onload = () => window.kakao.maps.load(() => resolve(window.kakao))
    s.onerror = () => { pending = null; reject(new Error('LOAD_FAIL')) }
    document.head.appendChild(s)
  })
  return pending
}

// 여러 키워드로 검색 후 중복 제거 · 거리순 정렬
export function searchRepairShops(kakao, center, radiusM, keywords = ['자동차정비', '공업사', '카센터']) {
  const places = new kakao.maps.services.Places()
  const one = (keyword) => new Promise((resolve) => {
    places.keywordSearch(keyword, (data, status) => {
      resolve(status === kakao.maps.services.Status.OK ? data : [])
    }, { location: center, radius: radiusM, sort: kakao.maps.services.SortBy.DISTANCE, size: 15 })
  })
  return Promise.all(keywords.map(one)).then((lists) => {
    const seen = new Set()
    const merged = []
    for (const p of lists.flat()) {
      if (seen.has(p.id)) continue
      if (!/자동차|정비|공업사|카센터|수리/.test(p.category_name + p.place_name)) continue
      seen.add(p.id)
      merged.push(p)
    }
    merged.sort((a, b) => Number(a.distance) - Number(b.distance))
    return merged.slice(0, 15)
  })
}

// 주소 문자열(또는 후보 배열)을 좌표로 변환. 배열이면 성공하는 첫 후보를 사용한다.
export function geocodeAddress(kakao, address) {
  const geocoder = new kakao.maps.services.Geocoder()
  const candidates = Array.isArray(address) ? address : [address]
  const tryOne = (i) => new Promise((resolve, reject) => {
    if (i >= candidates.length) return reject(new Error('GEOCODE_FAIL'))
    geocoder.addressSearch(candidates[i], (result, status) => {
      if (status === kakao.maps.services.Status.OK && result[0]) {
        resolve(new kakao.maps.LatLng(Number(result[0].y), Number(result[0].x)))
      } else tryOne(i + 1).then(resolve, reject)
    })
  })
  return tryOne(0)
}

export function formatDistance(m) {
  const n = Number(m)
  if (!n && n !== 0) return ''
  return n >= 1000 ? `${(n / 1000).toFixed(1)}km` : `${n}m`
}

// "교통,수송 > 자동차 > 자동차수리" → "자동차수리"
export function shopKind(p) {
  const name = p.place_name || ''
  if (/블루핸즈/.test(name)) return '현대 협력'
  if (/오토큐/.test(name)) return '기아 협력'
  if (/공업사/.test(name)) return '공업사'
  if (/카센터/.test(name)) return '카센터'
  const seg = (p.category_name || '').split('>').pop().trim()
  return seg || '정비소'
}

// 시·도/시·군·구 아래의 읍·면·동(또는 시 아래 구)을 좌표가 있는 행정구역으로 검색한다.
// prefixes: 상위 지역 주소 후보(새 명칭 → 이전 명칭). 성공하는 첫 후보를 사용한다.
// 카카오 주소 검색은 "역삼"처럼 접미사가 없으면 실패할 때가 있어 동·읍·면·구를 붙여 재시도한다.
export async function searchRegions(kakao, prefixes, query) {
  const q = (query || '').trim()
  if (!q) return []
  const geocoder = new kakao.maps.services.Geocoder()
  const one = (addr) => new Promise((resolve) => {
    geocoder.addressSearch(addr, (result, status) => {
      resolve(status === kakao.maps.services.Status.OK ? result.filter((r) => r.address_type === 'REGION') : [])
    })
  })
  const hasSuffix = /[동읍면구가리]$/.test(q)
  let found = []
  for (const prefix of Array.isArray(prefixes) ? prefixes : [prefixes]) {
    found = await one(`${prefix} ${q}`)
    if (!found.length && !hasSuffix) {
      const lists = await Promise.all(['동', '읍', '면', '구'].map((s) => one(`${prefix} ${q}${s}`)))
      found = lists.flat()
    }
    if (found.length) break
  }
  const seen = new Set()
  const out = []
  for (const r of found) {
    if (seen.has(r.address_name)) continue
    seen.add(r.address_name)
    const a = r.address || {}
    // 법정동 → 행정동 → 시 아래 구(region_2depth_name 의 마지막 어절) 순으로 표시명 결정
    const label = a.region_3depth_name || a.region_3depth_h_name || (a.region_2depth_name || '').split(' ').pop()
    out.push({ addr: r.address_name, label, lat: Number(r.y), lng: Number(r.x) })
  }
  return out
}
