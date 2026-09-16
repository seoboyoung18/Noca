// 견적 PDF 다운로드 흐름 — 요청(POST) → 상태 폴링(GET) → 완료본 다운로드(302) 세 단계를 한 함수로 묶는다.
// 서버는 PDF 를 워커가 비동기로 만들고(10초 주기, 최대 3회 재시도) 완료본은 S3 서명 URL 로만 준다.
// 리포트 화면과 사고 이력 화면이 같은 흐름을 쓴다. (backend EstimatePdfController · EstimatePdfService · EstimatePdfWorker)
import { onUnmounted, ref } from 'vue'
import { estimatePdfDownloadUrl, fetchEstimatePdfStatus, requestEstimatePdf } from './api'

const POLL_MS = 2000
const POLL_MAX = 90 // 3분. 워커 처리 제한(5분)보다 먼저 끊고 "잠시 후 다시" 로 안내한다

const FAIL_DEFAULT = 'PDF를 만들지 못했어요. 잠시 후 다시 시도해 주세요.'

export function useEstimatePdf() {
  // idle | checking(상태 확인·요청) | generating(워커 대기) | done(다운로드 시작) | error
  const step = ref('idle')
  const busyId = ref(null) // 진행 중인 견적 id — 목록 화면이 행마다 상태를 그릴 수 있게
  const message = ref('') // 화면이 토스트로 띄울 문구. 빈 문자열이면 없음
  let timer = null
  let stopped = false

  const wait = (ms) => new Promise((resolve) => { timer = setTimeout(resolve, ms) })

  function finish(nextStep, text) {
    step.value = nextStep
    message.value = text
    busyId.value = null
  }

  function openDownload(estimateId) {
    // 302 → S3 응답이 Content-Disposition: attachment 라 페이지를 벗어나지 않고 저장이 시작된다.
    // window.open 은 비동기 폴링 뒤라 팝업 차단에 걸리므로 쓰지 않는다.
    window.location.assign(estimatePdfDownloadUrl(estimateId))
    finish('done', 'PDF 다운로드를 시작했어요')
  }

  async function download(estimateId) {
    if (!estimateId || busyId.value) return
    stopped = false
    busyId.value = estimateId
    message.value = ''
    step.value = 'checking'
    try {
      let s = await fetchEstimatePdfStatus(estimateId)
      if (s?.status === 'COMPLETED') return openDownload(estimateId) // 이미 만든 파일이 있으면 바로 받는다
      if (!s || s.status === 'FAILED') {
        try { s = await requestEstimatePdf(estimateId) }
        catch (e) { if (e.status !== 409) throw e } // 409 = 다른 요청이 이미 생성 중 → 그 건을 기다린다
      }
      step.value = 'generating'
      for (let i = 0; i < POLL_MAX; i++) {
        await wait(POLL_MS)
        if (stopped) return
        s = await fetchEstimatePdfStatus(estimateId)
        if (s?.status === 'COMPLETED') return openDownload(estimateId)
        if (s?.status === 'FAILED') return finish('error', s.failureReason || FAIL_DEFAULT)
      }
      finish('error', 'PDF 생성이 오래 걸리고 있어요. 잠시 후 다시 눌러 주세요.')
    } catch (e) {
      if (stopped) return
      if (e.status === 401) { finish('idle', '') } // main.js 가 로그인으로 보낸다
      else if (e.status === 404) finish('error', '견적을 찾을 수 없어요.')
      else if (e.status === 503 || e.status === 0) finish('error', e.message) // 서버 문구가 사용자용
      else finish('error', FAIL_DEFAULT)
    }
  }

  onUnmounted(() => { stopped = true; clearTimeout(timer) }) // 화면을 떠나면 폴링을 반드시 끈다

  return { step, busyId, message, download }
}
