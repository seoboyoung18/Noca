package com.ssafy.a307.guide.service;

import com.ssafy.a307.guide.dto.OverlaySetMapping;
import com.ssafy.a307.guide.dto.ShootingGuideResponse;
import com.ssafy.a307.guide.dto.ShootingShot;
import com.ssafy.a307.vehicle.entity.VehicleType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code resources/shooting-guide.json} 의 실제 문안을 검증한다.
 * 기획이 JSON 을 고치다 깨뜨렸을 때 배포가 아니라 빌드에서 잡히게 하는 것이 목적이다.
 */
@DisplayName("촬영 가이드 문안 파일")
class ShootingGuideContentTest {

    private final GuideContentLoader loader = new GuideContentLoader(JsonMapper.builder().build());

    private ShootingGuideResponse shootingGuide() {
        return loader.load("shooting-guide.json", ShootingGuideResponse.class);
    }

    @Test
    @DisplayName("권장 1장, 컷 1개 — 파손 부위 한 장으로 분석한다")
    void shootingGuideShape() {
        ShootingGuideResponse guide = shootingGuide();

        // 2026-09-16 팀 결정으로 10컷 → 1컷. 이전 값(권장 10장·컷 10개·근접 2컷)은
        // 이 커밋 이전 이력에 있다.
        assertThat(guide.recommendedCount()).isEqualTo(1);
        assertThat(guide.shots()).extracting(shot -> shot.order()).containsExactly(1);

        ShootingShot only = guide.shots().get(0);
        // 나머지 8종은 차체의 특정 면에 묶여 있어 손상 위치에 따라 맞지 않는다.
        // DAMAGE_CLOSE 만 면과 무관하게 "파손 부위" 를 가리킨다.
        assertThat(only.angleCode()).isEqualTo("DAMAGE_CLOSE");
        // 한 장으로 부품 식별까지 해야 하므로 근접이 아니다 — 너무 붙으면 어느 부품인지 모른다.
        assertThat(only.closeUp()).isFalse();
    }

    @Test
    @DisplayName("업로드가 받아 주는 각도 어휘는 shots 와 분리돼 9종을 유지한다")
    void acceptedAngleCodesStayNine() {
        List<String> accepted = shootingGuide().acceptedAngleCodes();

        // 가이드는 "무엇을 찍으라고 안내하나" 이고 이 목록은 "무엇을 받아 주나" 다.
        // 가이드를 1컷으로 줄였다고 어휘까지 줄이면 repair_case_image.angle_tag 와 값 집합이
        // 어긋나고, 이미 FRONT 로 태그된 업로드가 400 이 된다.
        assertThat(accepted).containsExactlyInAnyOrder("FRONT", "REAR", "LEFT", "RIGHT",
                "FRONT_LEFT", "FRONT_RIGHT", "REAR_LEFT", "REAR_RIGHT", "DAMAGE_CLOSE");
        assertThat(accepted).contains(shootingGuide().shots().get(0).angleCode());
    }

    @Test
    @DisplayName("각도 코드가 repair_case_image.angle_tag 의 VARCHAR(20) 에 들어간다")
    void angleCodesFitColumn() {
        assertThat(shootingGuide().shots()).extracting(ShootingShot::angleCode)
                .allSatisfy(code -> assertThat(code.toString()).hasSizeLessThanOrEqualTo(20));
    }

    @Test
    @DisplayName("오버레이 세트 — 차량 유형 4종을 전부 덮고 VAN·TRUCK 은 SUV 로 폴백한다")
    void overlaySetsCoverEveryVehicleType() {
        Map<VehicleType, String> byType = shootingGuide().overlaySets().stream()
                .collect(Collectors.toMap(OverlaySetMapping::vehicleType, OverlaySetMapping::overlaySet));

        // ck_vm_type 이 허용하는 값이 늘어나면 이 테스트가 먼저 깨진다.
        assertThat(byType).containsOnlyKeys(VehicleType.values());
        assertThat(byType).containsEntry(VehicleType.SEDAN, "SEDAN")
                .containsEntry(VehicleType.SUV, "SUV")
                .containsEntry(VehicleType.VAN, "SUV")
                .containsEntry(VehicleType.TRUCK, "SUV");

        // 실루엣은 승용/SUV 2종만 디자인된다. 그 밖의 세트를 가리키면 FE 가 찾을 자산이 없다.
        assertThat(byType.values()).allMatch(set -> set.equals("SEDAN") || set.equals("SUV"));
    }
}
