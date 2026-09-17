package com.ssafy.a307.estimate.service;

import com.ssafy.a307.estimate.dto.EstimateNotice;
import com.ssafy.a307.estimate.entity.EstimateNoticeContent;
import com.ssafy.a307.estimate.repository.EstimateNoticeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 고지 문구 공급 경계 (S15P21A307-288 · S15P21A307-289).
 *
 * <p><b>왜 한 곳으로 모으나</b> — 견적 조회·리포트·PDF 가 같은 문장을 보여 줘야 한다는 것이
 * -289 의 요구다. 소비처마다 따로 읽으면 한 곳만 고치고 나머지를 잊는 일이 생긴다. 지금까지
 * 그것을 {@code EstimateValidationService.LEGAL_NOTICE} 상수 하나가 막아 주고 있었고, 그
 * 역할을 이 클래스가 이어받는다.
 *
 * <p><b>캐시를 두지 않는다.</b> 요청마다 한 행을 읽는다. 넣고 싶어지면 근거를 먼저 만든다 —
 * 이 조회가 느리다는 측정이 아직 없고, 캐시를 두는 순간 "psql 로 고쳤는데 화면이 안 바뀐다"
 * 라는 문제가 새로 생긴다. 그것은 -288 이 없애려던 재배포 대기와 성질이 같다.
 *
 * <p><b>없을 때 예외를 던지지 않는다.</b> 문구가 없으면 견적 조회는 빈 목록을, 리포트는
 * {@code null} 을 받는다. 리포트 쪽은 {@code EstimateReportService.requireSections()} 가
 * 이미 고지 문구를 필수로 보고 막아 준다(S15P21A307-338) — 여기서 또 던지면 같은 판단이
 * 두 곳에 생긴다.
 */
@Service
@RequiredArgsConstructor
public class EstimateNoticeProvider {

    /**
     * 견적 한계 고지. 사용자가 보험사·정비소에 들고 갈 문서라 반드시 함께 나가야 하는 문장이다.
     *
     * <p>값이 아니라 <b>코드</b>를 상수로 둔다. 문장을 상수로 두면 -288 이 그 자리에서 깨진다.
     */
    public static final String LEGAL_NOTICE_CODE = "LEGAL_NOTICE";

    /**
     * 안내 한계 고지 (S15P21A307-487). 체크리스트·질문 화면 상단이 쓴다. 리포트에는 싣지 않는다
     * (S15P21A307-533).
     *
     * <p><b>왜 이 테이블에 있나.</b> {@code estimate_notice} 는 구조가 범용
     * ({@code code}·{@code message}·{@code display_order}·{@code is_active})이라 그대로 담기고,
     * 새 테이블을 만드는 것은 스키마 작업이라 {@code -487}(1pt)의 범위를 넘는다. <b>대가는
     * 테이블 이름과 내용이 어긋나는 것</b>이고, 그 어긋남이 실제로 부른 문제가 바로 아래
     * {@link #NON_ESTIMATE_CODES} 다. 제대로 고치려면 {@code scope} 열이나 별도 테이블이
     * 필요하고, 그것은 별도 티켓이다.
     */
    public static final String GUIDANCE_LIMIT_NOTICE_CODE = "GUIDANCE_LIMIT_NOTICE";

    /**
     * 견적 화면이 실으면 <b>안 되는</b> 코드.
     *
     * <p>{@link #activeNotices()} 는 "견적 조회에 함께 싣는 문구 전부" 라는 뜻인데, 이 테이블이
     * 다른 기능의 문구까지 담게 되면서 "활성 행 전부" 가 더 이상 그 뜻이 아니게 됐다.
     * {@link #GUIDANCE_LIMIT_NOTICE_CODE} 의 문장은 "본 <b>체크리스트와 질문은</b> …" 으로
     * 시작한다 — 견적 화면에 그대로 나가면 있지도 않은 섹션을 가리킨다.
     *
     * <p><b>싣는 코드를 나열하지 않고 빼는 코드를 나열한다.</b> 반대로 만들면 운영자가
     * {@code psql INSERT} 로 새 견적 문구를 넣어도 화면에 안 나와, 재배포 없이 문구를 바꾼다는
     * {@code S15P21A307-288} 의 요구가 그 자리에서 깨진다.
     */
    private static final Set<String> NON_ESTIMATE_CODES = Set.of(GUIDANCE_LIMIT_NOTICE_CODE);

    private final EstimateNoticeRepository noticeRepository;

    /**
     * 견적 조회에 함께 싣는 문구 전부 ({@code GET /api/estimates/{id}} 의 {@code notices[]}).
     *
     * @return 활성 문구 중 견적 것만. 하나도 없으면 빈 목록 — 오류가 아니다
     */
    @Transactional(readOnly = true)
    public List<EstimateNotice> activeNotices() {
        return noticeRepository.findByActiveTrueOrderByDisplayOrderAscCodeAsc().stream()
                .filter(content -> !NON_ESTIMATE_CODES.contains(content.getCode()))
                .map(content -> new EstimateNotice(content.getCode(), content.getMessage()))
                .toList();
    }

    /**
     * 체크리스트·질문 화면이 쓰는 안내 한계 고지 (S15P21A307-487). 리포트에는 싣지 않는다
     * (S15P21A307-533).
     *
     * <p><b>없어도 예외를 던지지 않는다.</b> 시드가 빠진 환경에서 이 문구 하나 때문에 체크리스트
     * 조회 전체가 죽으면 안 된다 — {@code LEGAL_NOTICE} 는
     * {@code EstimateReportService.requireSections()} 가 필수로 막고 있지만, 그 강도를 여기에
     * 그대로 옮기면 2026-09-14 에 났던 "시드가 없어 리포트 전체가 500" 이 한 번 더 난다.
     *
     * @return 문구. 행이 없거나 내려 둔 상태면 {@code null}
     */
    @Transactional(readOnly = true)
    public String guidanceLimitNotice() {
        return noticeRepository.findByCodeAndActiveTrue(GUIDANCE_LIMIT_NOTICE_CODE)
                .map(EstimateNoticeContent::getMessage)
                .orElse(null);
    }

    /**
     * 리포트·PDF 가 쓰는 단일 고지 문장.
     *
     * <p>목록이 아니라 문자열을 돌려주는 것은 {@code EstimateReportResponse.legalNotice} 와
     * 검증 쪽 응답이 이미 단일 문자열이기 때문이다. 계약을 바꾸지 않고 <b>출처만</b> 옮긴다.
     *
     * @return 문구. 행이 없거나 내려 둔 상태면 {@code null}
     */
    @Transactional(readOnly = true)
    public String legalNotice() {
        return noticeRepository.findByCodeAndActiveTrue(LEGAL_NOTICE_CODE)
                .map(EstimateNoticeContent::getMessage)
                .orElse(null);
    }
}
