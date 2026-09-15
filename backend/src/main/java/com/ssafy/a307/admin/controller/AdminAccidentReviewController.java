package com.ssafy.a307.admin.controller;

import com.ssafy.a307.admin.dto.AccidentReviewDecisionRequest;
import com.ssafy.a307.admin.dto.AccidentReviewResponse;
import com.ssafy.a307.admin.dto.AdminPageRequest;
import com.ssafy.a307.admin.dto.AdminPageResponse;
import com.ssafy.a307.admin.service.AdminAccidentReviewService;
import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.review.entity.AccidentReviewStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 사고 데이터·피드백 검수 (S15P21A307-350 목록 · -352 승인·반려).
 *
 * <p><b>{@code /api/admin/**} 은 {@code SecurityConfig} 가 {@code hasRole("ADMIN")} 으로 막는다.</b>
 * 비로그인은 401, 일반 {@code USER} 는 403 이다 — <b>보안 설정을 새로 만들지 않았다.</b>
 *
 * <p><b>관리자 ID 를 요청으로 받지 않는다.</b> 어떤 DTO 에도 actor 필드가 없고, 판정자와 감사
 * 로그의 행위자는 {@code CurrentMemberProvider} 가 세션에서 꺼낸다.
 *
 * <p><b>큐에 넣는 API 가 없다.</b> 적재는 사용자가 실제 수리비를 입력할 때 자동으로 일어난다
 * ({@code AccidentReviewQueueService}) — 관리자가 손으로 큐를 만들 이유가 없고, 만들 수 있게
 * 하면 재학습에 쓸 수 없는 건이 큐에 섞인다.
 *
 * <p><b>AI 결과 ↔ 사용자 수정 내역 비교 상세는 여기 없다.</b> 그 티켓이 아직 없다(answer71 §6-1).
 */
@RestController
@RequestMapping("/api/admin/accident-reviews")
@RequiredArgsConstructor
public class AdminAccidentReviewController {

    /** 정렬 화이트리스트. 벗어나면 {@code AdminPageRequest} 가 조용히 기본 정렬로 되돌린다. */
    private static final Set<String> SORTABLE = Set.of("queuedAt", "reviewedAt", "reviewId");

    /** 오래 기다린 것부터. {@code ix_ar_queue (status, queued_at)} 를 그대로 탄다. */
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.ASC, "queuedAt");

    private final AdminAccidentReviewService service;

    /**
     * 검수 목록. {@code status} 를 주지 않으면 전체다.
     *
     * <p>대기 큐만 보려면 {@code ?status=PENDING} 이다 — 그 조합이 부분 인덱스를 탄다.
     */
    @GetMapping
    public ApiResponse<AdminPageResponse<AccidentReviewResponse>> search(
            @RequestParam(required = false) AccidentReviewStatus status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {

        return ApiResponse.of(service.search(status,
                AdminPageRequest.of(page, size, sort, DEFAULT_SORT, SORTABLE)));
    }

    /**
     * 승인·반려 (S15P21A307-352).
     *
     * <p>본문은 {@code decision} 과 {@code rejectReason} 이다. <b>반려에는 사유가 필수이고
     * 승인에는 보내면 안 된다</b> — 어긋나면 400 이다({@code AccidentReviewDecisionRequest}).
     * 없는 검수 대상은 404({@code ADMIN_TARGET_NOT_FOUND})다.
     */
    @PatchMapping("/{reviewId}")
    public ApiResponse<AccidentReviewResponse> decide(
            @PathVariable Long reviewId,
            @Valid @RequestBody AccidentReviewDecisionRequest request) {

        return ApiResponse.of(service.decide(reviewId, request));
    }
}
