package com.ssafy.a307.analysis.controller;

import com.ssafy.a307.analysis.dto.AnalysisProgressResponse;
import com.ssafy.a307.analysis.service.AnalysisProgressService;
import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.common.security.CurrentMemberProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 분석 진행 상태 (화면 5 · 10).
 *
 * <p><b>사고 기준 경로다.</b> 화면이 손에 쥐고 있는 것은 {@code accidentId} 이고 {@code jobId} 는
 * 서버가 정하는 값이라, 사고로 물어보면 재분석으로 작업이 새로 생겨도 화면이 URL 을 바꾸지 않는다.
 * 여러 작업 중 <b>가장 최근 것</b>을 본다.
 *
 * <p>회원 ID 는 {@link CurrentMemberProvider} 에서만 얻는다 — 경로·본문으로 받지 않는다.
 * {@code AccidentController}·{@code AccidentImageController} 와 같은 방식이다.
 *
 * <p><b>조회만 있다.</b> 분석을 요청하거나 단계를 진행시키는 경로는 이 컨트롤러에 없다
 * ({@code S15P21A307-155} 범위).
 */
@RestController
@RequestMapping("/api/accidents/{accidentId}/analysis")
@RequiredArgsConstructor
public class AnalysisProgressController {

    private final AnalysisProgressService analysisProgressService;
    private final CurrentMemberProvider currentMemberProvider;

    /**
     * 진행 상태. 없는 사고·남의 사고는 404, 분석을 아직 요청하지 않은 사고는 <b>빈 상태 200</b> 이다.
     */
    @GetMapping
    public ApiResponse<AnalysisProgressResponse> progress(@PathVariable Long accidentId) {
        return ApiResponse.of(analysisProgressService.progress(
                currentMemberProvider.currentMemberId(), accidentId));
    }
}
