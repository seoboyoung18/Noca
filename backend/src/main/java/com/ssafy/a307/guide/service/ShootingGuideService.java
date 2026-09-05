package com.ssafy.a307.guide.service;

import com.ssafy.a307.guide.dto.ShootingGuideResponse;
import org.springframework.stereotype.Service;

/**
 * 각도별 촬영 가이드 제공.
 *
 * <p>{@link ChecklistService} 와 같은 구조다 — DB 없음, 기동 시점 1회 로드.
 * 오버레이·예시 이미지는 서버가 다루지 않는다 ({@code prompt12.md} 2장).
 */
@Service
public class ShootingGuideService {

    private static final String CONTENT_LOCATION = "shooting-guide.json";

    private final ShootingGuideResponse shootingGuide;

    public ShootingGuideService(GuideContentLoader loader) {
        this.shootingGuide = loader.load(CONTENT_LOCATION, ShootingGuideResponse.class);
    }

    public ShootingGuideResponse getShootingGuide() {
        return shootingGuide;
    }
}
