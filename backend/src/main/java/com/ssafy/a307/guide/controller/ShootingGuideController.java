package com.ssafy.a307.guide.controller;

import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.guide.dto.ShootingGuideResponse;
import com.ssafy.a307.guide.service.ShootingGuideService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 각도별 촬영 가이드. <b>인증이 필요 없다</b> — 사고 직후 로그인 없이 봐야 하는 화면이다.
 *
 * <p>유형별 필터 없이 전체를 내려준다. 오버레이·예시 이미지 URL 은 넣지 않는다 —
 * 카메라 화면 위 실시간 오버레이는 네트워크 왕복을 기다릴 수 없어 이미지는 FE 번들에 둔다.
 */
@RestController
@RequestMapping("/api/guides")
@RequiredArgsConstructor
public class ShootingGuideController {

    private final ShootingGuideService shootingGuideService;

    /** 각도별 촬영 가이드. 요청 파라미터 없음. */
    @GetMapping("/shooting")
    public ApiResponse<ShootingGuideResponse> getShootingGuide() {
        return ApiResponse.of(shootingGuideService.getShootingGuide());
    }
}
