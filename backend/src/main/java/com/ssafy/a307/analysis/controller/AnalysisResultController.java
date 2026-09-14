package com.ssafy.a307.analysis.controller;

import com.ssafy.a307.analysis.dto.AnalysisResultResponse;
import com.ssafy.a307.analysis.service.AnalysisResultService;
import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.common.security.CurrentMemberProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 분석 결과 (화면 11 · 손상분석) — S15P21A307-203.
 *
 * <p><b>사고 기준 경로다.</b> 진행 상태({@code GET /api/accidents/{accidentId}/analysis})와 같은
 * 기준이라 화면이 두 API 를 같은 id 로 부른다. {@code jobId} 는 서버가 정하는 값이어서 재분석으로
 * 작업이 새로 생겨도 URL 이 바뀌지 않는다 — {@code AnalysisProgressController} 와 같은 판단이다.
 *
 * <p>회원 ID 는 {@link CurrentMemberProvider} 에서만 얻는다 — 경로·본문으로 받지 않는다.
 *
 * <p><b>조회만 있다.</b> 결과를 만드는 것은 AI 서버가 부르는 내부 callback
 * ({@code POST /internal/analysis-jobs/{jobId}/result}, S15P21A307-157)이다.
 */
@RestController
@RequestMapping("/api/accidents/{accidentId}/analysis")
@RequiredArgsConstructor
public class AnalysisResultController {

    private final AnalysisResultService analysisResultService;
    private final CurrentMemberProvider currentMemberProvider;

    /**
     * 검출된 부위와 사진별 좌표. 없는 사고·남의 사고는 404, 분석 전인 사고는 <b>빈 상태 200</b>.
     */
    @GetMapping("/result")
    public ApiResponse<AnalysisResultResponse> result(@PathVariable Long accidentId) {
        return ApiResponse.of(analysisResultService.result(
                currentMemberProvider.currentMemberId(), accidentId));
    }
}
