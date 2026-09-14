package com.ssafy.a307.repairchecklist.dto;

import com.ssafy.a307.repairchecklist.entity.RepairChecklist;
import com.ssafy.a307.repairchecklist.entity.RepairChecklistStatus;

import java.time.Instant;

/**
 * 체크리스트 생성 상태 (S15P21A307-460 응답 · -461 조회).
 *
 * <h2>상태를 네 값 그대로 준다</h2>
 *
 * <p>{@code QUEUED · PROCESSING · COMPLETED · FAILED}. {@code -461} 제목의 "대기·완료·실패" 로
 * 접지 않는다 — {@code QUEUED} 와 {@code PROCESSING} 을 합칠지는 화면 결정이고, 서버가 미리
 * 접으면 FE 가 "생성 중" 을 구분해 보여 줄 수 없다. <b>한글 라벨을 내려보내지 않는다</b>
 * ({@code AnalysisProgressResponse} 와 같은 규칙).
 *
 * <h2>진행률을 주지 않는다</h2>
 *
 * <p>체크 개수·퍼센트는 여기 없다. 담을 열이 정본 DDL 에 없고({@code -509} 가 일부러 두지
 * 않았다) 항목 조회·체크는 각자의 스토리({@code S15P21A307-483} · {@code -485})다. 필요해지면
 * {@code repair_checklist_item} 을 {@code COUNT} 두 번 세면 나온다.
 *
 * <h2>빈 상태</h2>
 *
 * <p>사고는 있는데 아직 생성을 요청하지 않았다면 <b>오류가 아니라 빈 상태 200</b> 이다
 * ({@link #notRequested()}). 404 로 내면 화면이 "없는 사고" 와 "아직 요청 전" 을 구분하지 못한다.
 *
 * @param status         네 값 중 하나. 빈 상태에서는 {@code null}
 * @param failureReason  실패 <b>코드</b>다. 한글 문구가 아니다
 *                       ({@link com.ssafy.a307.repairchecklist.domain.RepairChecklistFailure})
 * @param generationNo   몇 번째 생성분인가. 재생성({@code S15P21A307-486})이 올린다
 */
public record RepairChecklistStatusResponse(
        Long checklistId,
        RepairChecklistStatus status,
        short generationNo,
        String failureReason,
        Instant createdAt,
        Instant completedAt,
        Instant regeneratedAt) {

    /** 아직 생성을 요청하지 않은 사고. 오류가 아니다. */
    public static RepairChecklistStatusResponse notRequested() {
        return new RepairChecklistStatusResponse(null, null, (short) 0, null, null, null, null);
    }

    public static RepairChecklistStatusResponse from(RepairChecklist checklist) {
        return new RepairChecklistStatusResponse(
                checklist.getChecklistId(),
                checklist.getStatus(),
                checklist.getGenerationNo(),
                checklist.getFailureReason(),
                checklist.getCreatedAt(),
                checklist.getCompletedAt(),
                checklist.getRegeneratedAt());
    }
}
