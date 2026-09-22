package com.ssafy.a307.analysis.request;

import com.ssafy.a307.analysis.dto.AnalysisProgressResponse;
import com.ssafy.a307.analysis.dto.ResolvePartRequest;
import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.common.security.CurrentMemberProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 부위 확정 재분석 (S15P21A307-570).
 *
 * <p><b>견적 기준 경로다.</b> 화면(S15P21A307-567)이 산정 불가 견적을 보다가 부위를 고르므로 견적
 * id 를 들고 있다. 하는 일은 분석을 다시 요청하는 것이라 {@link AnalysisRequestService} 가 받는다.
 * 응답도 분석 요청·재시도와 같은 진행 상태 모양이라, 화면은 이후 진행 조회로 이어 가면 된다.
 *
 * <p>회원 ID 는 {@link CurrentMemberProvider} 에서만 얻는다 — 경로·본문으로 받지 않는다.
 */
@RestController
@RequiredArgsConstructor
public class ResolvePartController {

    private final AnalysisRequestService analysisRequestService;
    private final CurrentMemberProvider currentMemberProvider;

    /**
     * 고른 부위로 다시 분석한다. 같은 사고에 작업을 새로 만들어 접수하고 202 다.
     * 404(없는·남의 견적) · 503(AI 설정 없음) · 400(고를 수 없는 부위·보낼 사진 없음) ·
     * 409(진행 중·최신 견적 아님·부위 선택 대상 아님·3회 초과).
     */
    @PostMapping("/api/estimates/{estimateId}/resolve-part")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AnalysisProgressResponse> resolvePart(@PathVariable Long estimateId,
                                                            @Valid @RequestBody ResolvePartRequest request) {
        return ApiResponse.of(analysisRequestService.resolvePart(
                currentMemberProvider.currentMemberId(), estimateId, request.partCode()));
    }
}
