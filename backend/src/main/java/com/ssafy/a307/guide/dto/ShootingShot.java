package com.ssafy.a307.guide.dto;

/**
 * 권장 촬영 컷 하나.
 *
 * <p>{@code angleCode} 는 {@code repair_case_image.angle_tag} 와 <b>같은 값 집합</b>을 쓴다.
 * 나중에 사용자 사고 이미지와 학습 사례 이미지를 각도 기준으로 비교하려면 두 값이 같아야 한다
 * (answer12.md 2장).
 *
 * <p>오버레이·예시 이미지 URL 은 넣지 않는다. 이미지는 FE 번들에 있고 서버는 코드만 준다
 * — 카메라 화면 위 실시간 오버레이는 네트워크 왕복을 기다릴 수 없기 때문이다
 * ({@code prompt12.md} 2장).
 */
public record ShootingShot(
        int order,
        String angleCode,
        String title,
        String description,
        boolean closeUp
) {
}
