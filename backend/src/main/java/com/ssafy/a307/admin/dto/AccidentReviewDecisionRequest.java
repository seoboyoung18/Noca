package com.ssafy.a307.admin.dto;

import com.ssafy.a307.review.entity.AccidentReview;
import com.ssafy.a307.review.entity.AccidentReviewStatus;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 승인·반려 판정 (S15P21A307-352).
 *
 * <h2>왜 한 엔드포인트인가</h2>
 *
 * <p>{@code S15P21A307-483} 은 체크와 메모를 <b>두 경로로 나눴다</b>. 하나의 {@code PATCH} 로
 * 받으면 {@code null} 이 "안 보냄" 인지 "지움" 인지 구분되지 않아 메모를 지울 길이 없었기
 * 때문이다. <b>여기는 그 문제가 없다</b> — 판정은 덮어쓰기지 부분 갱신이 아니고,
 * {@code rejectReason} 은 {@code REJECTED} 일 때만 오는 값이라 해석이 갈리지 않는다.
 *
 * <p>그래서 이 저장소의 관리자 관례를 그대로 따른다. {@code AdminPartCodeController} 의
 * {@code PATCH /{partCode}/status} 가 {@code active} 와 {@code changeReason} 을 한 본문으로
 * 받는 것과 같은 모양이다 — <b>새 관례를 만들지 않는다</b>(prompt72 §3-4).
 *
 * <h2>불가능한 조합을 400 으로 먼저 막는다</h2>
 *
 * <p>{@code ck_ar_reject} 가 {@code (status = 'REJECTED') = (reject_reason IS NOT NULL)} 로
 * <b>양방향</b>이라, 어긋난 조합을 그대로 엔티티에 넘기면 DB 가 거부해 <b>500</b> 이 된다.
 * {@link #reasonMatchesDecision()} 이 그 조합을 검증 단계에서 잡아 400 으로 되돌린다 —
 * 잘못된 요청은 서버 오류가 아니다.
 *
 * @param decision     {@code APPROVED} 또는 {@code REJECTED}. {@code PENDING} 으로 되돌리는
 *                     경로는 없다 — 재검수 정책이 아직 정해지지 않았다(answer71 §7-3)
 * @param rejectReason 반려 사유. <b>반려일 때 필수, 승인일 때는 보내면 안 된다</b>
 */
public record AccidentReviewDecisionRequest(
        @NotNull AccidentReviewStatus decision,
        @Size(max = AccidentReview.MAX_REJECT_REASON_LENGTH) String rejectReason) {

    @AssertTrue(message = "반려에는 사유가 필요하고, 승인에는 사유를 보내지 않습니다.")
    public boolean isReasonMatchesDecision() {
        if (decision == null) {
            return true;    // @NotNull 이 따로 잡는다
        }
        boolean hasReason = rejectReason != null && !rejectReason.isBlank();
        return switch (decision) {
            case REJECTED -> hasReason;
            case APPROVED -> !hasReason;
            case PENDING -> false;
        };
    }
}
