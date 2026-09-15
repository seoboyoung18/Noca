package com.ssafy.a307.repairquestion.dto;

import com.ssafy.a307.repairquestion.entity.RepairQuestion;
import com.ssafy.a307.repairquestion.entity.RepairQuestionStatus;

import java.time.Instant;
import java.util.List;

/**
 * 질문 목록 생성 상태와 질문들 (S15P21A307-477 요청 응답 · 조회).
 *
 * <h2>상태를 네 값 그대로 준다</h2>
 *
 * <p>{@code QUEUED · PROCESSING · COMPLETED · FAILED}. {@code QUEUED} 와 {@code PROCESSING} 을
 * 합칠지는 화면 결정이고, 서버가 미리 접으면 FE 가 "생성 중" 을 구분해 보여 줄 수 없다.
 * <b>한글 라벨을 내려보내지 않는다.</b>
 *
 * <h2>항목을 함께 준다 — 체크리스트와 다른 점이다</h2>
 *
 * <p>{@code RepairChecklistStatusResponse} 는 머리 상태만 주고 항목 조회를 별도 스토리
 * ({@code S15P21A307-483})로 미뤘다. 질문은 그럴 수 없다 — {@code S15P21A307-476} 이
 * <b>전체 복사</b>를 요구하므로 화면이 한 번에 전부 들고 있어야 하고, 한 사고의 질문은 최대
 * 열 줄이라 나눠 줄 이유가 없다. 스토리도 {@code -477} 하나가 "생성 요청 · 저장 · 조회" 를 전부
 * 안고 있다.
 *
 * <p>완성 전이거나 실패한 목록은 <b>빈 목록</b>이다. {@code null} 이 아니다 — 화면이 길이만 보고
 * 그릴 수 있어야 한다.
 *
 * <h2>빈 상태</h2>
 *
 * <p>사고는 있는데 아직 생성을 요청하지 않았다면 <b>오류가 아니라 빈 상태 200</b> 이다
 * ({@link #notRequested()}). 404 로 내면 화면이 "없는 사고" 와 "아직 요청 전" 을 구분하지 못한다.
 *
 * @param status        네 값 중 하나. 빈 상태에서는 {@code null}
 * @param failureReason 실패 <b>코드</b>다. 한글 문구가 아니다
 *                      ({@link com.ssafy.a307.repairquestion.domain.RepairQuestionFailure})
 * @param generationNo  몇 번째 생성분인가. 재생성이 올린다
 * @param questions     {@code display_order} 순. 완성 전에는 빈 목록
 */
public record RepairQuestionResponse(
        Long questionId,
        RepairQuestionStatus status,
        short generationNo,
        String failureReason,
        Instant createdAt,
        Instant completedAt,
        Instant regeneratedAt,
        List<RepairQuestionItemResponse> questions) {

    /** 아직 생성을 요청하지 않은 사고. 오류가 아니다. */
    public static RepairQuestionResponse notRequested() {
        return new RepairQuestionResponse(null, null, (short) 0, null, null, null, null, List.of());
    }

    public static RepairQuestionResponse from(RepairQuestion question,
                                              List<RepairQuestionItemResponse> questions) {
        return new RepairQuestionResponse(
                question.getQuestionId(),
                question.getStatus(),
                question.getGenerationNo(),
                question.getFailureReason(),
                question.getCreatedAt(),
                question.getCompletedAt(),
                question.getRegeneratedAt(),
                questions == null ? List.of() : List.copyOf(questions));
    }
}
