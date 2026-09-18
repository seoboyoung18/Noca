package com.ssafy.a307.repairchecklist.dto;

import com.ssafy.a307.repairchecklist.entity.RepairChecklist;
import com.ssafy.a307.repairchecklist.entity.RepairChecklistStatus;

import java.time.Instant;
import java.util.List;

/**
 * 체크리스트 생성 상태와 항목 (S15P21A307-460 응답 · -461 조회 · -483 항목·진행률).
 *
 * <h2>상태를 네 값 그대로 준다</h2>
 *
 * <p>{@code QUEUED · PROCESSING · COMPLETED · FAILED}. {@code -461} 제목의 "대기·완료·실패" 로
 * 접지 않는다 — {@code QUEUED} 와 {@code PROCESSING} 을 합칠지는 화면 결정이고, 서버가 미리
 * 접으면 FE 가 "생성 중" 을 구분해 보여 줄 수 없다. <b>한글 라벨을 내려보내지 않는다</b>
 * ({@code AnalysisProgressResponse} 와 같은 규칙).
 *
 * <h2>항목을 이 응답에 함께 담는다 (S15P21A307-483)</h2>
 *
 * <p>{@code -460} 이 만들 때는 머리 정보만 있었다. 별도 엔드포인트를 두지 않고 <b>여기에 더한</b>
 * 이유는 셋이다.
 *
 * <ol>
 *   <li>{@code -482} 가 "마이페이지 &gt; 나의 체크리스트에서 사용할 수 있다" 고 했다. 그 화면은
 *       상태·항목·진행률을 <b>한 번에</b> 그린다 — 두 번 호출하면 그 사이에 체크가 들어와
 *       진행률과 항목이 어긋나 보이는 창이 생긴다</li>
 *   <li>사고당 한 행이라 항목이 많아야 <b>AI 최대 10 + 공통 6</b> 이다. 페이지네이션할 크기가 아니다</li>
 *   <li>진행률을 따로 계산하지 않아도 된다 — 이미 읽은 항목을 세면 나온다
 *       ({@link RepairChecklistProgress})</li>
 * </ol>
 *
 * <p>생성이 끝나지 않았으면 항목은 <b>빈 목록</b>이다. {@code null} 이 아니다 — 화면이 길이만
 * 보고 그릴 수 있어야 한다.
 *
 * <h2>고지 문구를 함께 싣는다 (S15P21A307-487)</h2>
 *
 * <p>{@code -487} 이 "화면 <b>상단</b>과 리포트에 항상 표시한다" 고 요구한다. 화면이 상단에
 * 띄우려면 이 응답에 있어야 하고, 별도 호출로 나누면 체크리스트는 떴는데 고지는 안 뜨는 창이
 * 생긴다. 문안은 DB 에 있고 서버는 코드만 안다({@code EstimateNoticeProvider}).
 *
 * @param status        네 값 중 하나. 빈 상태에서는 {@code null}
 * @param failureReason 실패 <b>코드</b>다. 한글 문구가 아니다
 *                      ({@link com.ssafy.a307.repairchecklist.domain.RepairChecklistFailure})
 * @param generationNo  몇 번째 생성분인가. 재생성({@code S15P21A307-486})이 올린다
 * @param summary       AI 한 줄 요약 (S15P21A307-544). <b>없으면 {@code null}</b> — 이
 *                      변경 이전에 만들어진 체크리스트에는 없고, 화면은 그때 요약 영역을
 *                      그리지 않는다. <b>생성이 끝나기 전에도 {@code null}</b> 이다 —
 *                      항목과 같은 규칙이다
 * @param items         {@code display_order} 순. 생성 전·중에는 빈 목록
 * @param progress      완료/전체 두 수. <b>퍼센트가 아니다</b>
 * @param notice        안내 한계 고지 문구. <b>없으면 {@code null}</b> — 시드가 빠진 환경에서
 *                      체크리스트 조회 전체를 죽이지 않는다
 */
public record RepairChecklistStatusResponse(
        Long checklistId,
        RepairChecklistStatus status,
        short generationNo,
        String failureReason,
        Instant createdAt,
        Instant completedAt,
        Instant regeneratedAt,
        String summary,
        List<RepairChecklistItemResponse> items,
        RepairChecklistProgress progress,
        String notice) {

    /** 아직 생성을 요청하지 않은 사고. 오류가 아니다. */
    public static RepairChecklistStatusResponse notRequested(String notice) {
        return new RepairChecklistStatusResponse(null, null, (short) 0, null, null, null, null,
                null, List.of(), RepairChecklistProgress.EMPTY, notice);
    }

    /**
     * 항목을 실을 수 없는 자리 — 생성 <b>접수</b> 응답이다. 이제 막 큐에 들어갔으므로 항목이 없고,
     * 재시도로 큐에 되돌린 건도 앞 시도의 항목을 그대로 보여 주면 최신으로 오해된다.
     */
    public static RepairChecklistStatusResponse from(RepairChecklist checklist) {
        return from(checklist, List.of(), null);
    }

    public static RepairChecklistStatusResponse from(RepairChecklist checklist,
                                                     List<RepairChecklistItemResponse> items,
                                                     String notice) {
        List<RepairChecklistItemResponse> copied = items == null ? List.of() : List.copyOf(items);
        return new RepairChecklistStatusResponse(
                checklist.getChecklistId(),
                checklist.getStatus(),
                checklist.getGenerationNo(),
                checklist.getFailureReason(),
                checklist.getCreatedAt(),
                checklist.getCompletedAt(),
                checklist.getRegeneratedAt(),
                summaryOf(checklist),
                copied,
                RepairChecklistProgress.of(copied),
                notice);
    }

    /**
     * <b>생성이 끝난 것만 요약을 준다.</b> 항목을 {@code COMPLETED} 일 때만 싣는 것과 같은
     * 규칙이다 — 재생성으로 큐에 돌아간 건은 앞 세대의 요약이 아직 행에 남아 있고, 그것을
     * 그대로 주면 항목은 비었는데 요약만 예전 것인 화면이 된다.
     *
     * <p>행을 지우지는 않는다. 다음 생성이 덮어쓸 값이고, 생성이 실패하면 그때까지 남아 있던
     * 요약이 기록으로는 쓸모가 있다.
     */
    private static String summaryOf(RepairChecklist checklist) {
        return checklist.getStatus() == RepairChecklistStatus.COMPLETED
                ? checklist.getSummary()
                : null;
    }
}
