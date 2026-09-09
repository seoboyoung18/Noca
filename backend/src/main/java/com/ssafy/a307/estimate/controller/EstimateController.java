package com.ssafy.a307.estimate.controller;

import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.estimate.dto.EstimateResponse;
import com.ssafy.a307.estimate.dto.EstimateSummaryResponse;
import com.ssafy.a307.estimate.service.EstimateQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 예상 견적 조회.
 *
 * <p><b>생성 API 가 없다.</b> 견적은 분석이 끝나면 서버가 자동으로 산정한다
 * (S15P21A307-258). 사용자가 "견적 내줘"라고 부르는 경로는 없다.
 *
 * <p>회원은 {@code @AuthenticationPrincipal} 에서 꺼낸다 — {@code CurrentMemberProvider} 는
 * 아직 본문이 비어 있어 호출하면 500 이 난다. 인증 도메인이 쓰는 방식과 같다.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class EstimateController {

    private final EstimateQueryService estimateQueryService;

    /**
     * 견적 상세. 항목 목록과 고지 문구를 함께 준다 — 화면 한 장에 필요한 것을 한 번에 받는다.
     */
    @GetMapping("/estimates/{estimateId}")
    public ApiResponse<EstimateResponse> detail(@PathVariable Long estimateId,
                                                @AuthenticationPrincipal UserPrincipal principal) {

        return ApiResponse.of(estimateQueryService.detail(estimateId, principal.getMemberId()));
    }

    /**
     * 사고에 딸린 견적 이력. 재산정할 때마다 버전이 쌓인다.
     *
     * @param latest 기본 {@code true} — 최신 한 건만 준다. 이력 전체는 {@code false} 로 부른다
     */
    @GetMapping("/accidents/{accidentId}/estimates")
    public ApiResponse<List<EstimateSummaryResponse>> history(
            @PathVariable Long accidentId,
            @RequestParam(defaultValue = "true") boolean latest,
            @AuthenticationPrincipal UserPrincipal principal) {

        return ApiResponse.of(
                estimateQueryService.history(accidentId, principal.getMemberId(), latest));
    }
}
