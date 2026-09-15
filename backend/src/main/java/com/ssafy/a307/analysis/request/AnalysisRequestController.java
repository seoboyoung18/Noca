package com.ssafy.a307.analysis.request;

import com.ssafy.a307.analysis.dto.AnalysisProgressResponse;
import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.common.security.CurrentMemberProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 분석 요청 (S15P21A307-156).
 *
 * <p><b>진행 상태 조회({@code GET} 같은 경로)와 같은 사고 기준 경로다.</b> 응답도 그 조회와 같은
 * 모양이라, 화면은 요청 직후 받은 값으로 바로 그리고 이후 조회로 이어 가면 된다.
 *
 * <p>회원 ID 는 {@link CurrentMemberProvider} 에서만 얻는다 — 경로·본문으로 받지 않는다.
 */
@RestController
@RequestMapping("/api/accidents/{accidentId}/analysis")
@RequiredArgsConstructor
public class AnalysisRequestController {

    private final AnalysisRequestService analysisRequestService;
    private final CurrentMemberProvider currentMemberProvider;

    /**
     * 접수만 하고 202 다. AI 호출은 워커가 한다.
     * 404(없는·남의 사고) · 503(AI 설정 없음) · 409(이미 작업 있음) · 400(보낼 사진 없음).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AnalysisProgressResponse> request(@PathVariable Long accidentId) {
        return ApiResponse.of(analysisRequestService.request(
                currentMemberProvider.currentMemberId(), accidentId));
    }
}
