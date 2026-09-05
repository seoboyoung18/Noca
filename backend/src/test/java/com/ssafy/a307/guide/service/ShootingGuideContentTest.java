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
    @DisplayName("권장 10장, 컷 10개, 각도 코드 9종, 근접 2컷")
    void shootingGuideShape() {
        ShootingGuideResponse guide = shootingGuide();

        assertThat(guide.recommendedCount()).isEqualTo(10);
        assertThat(guide.shots()).extracting(shot -> shot.order())
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

        List<String> codes = guide.shots().stream().map(ShootingShot::angleCode).toList();
        assertThat(codes).containsOnly("FRONT", "REAR", "LEFT", "RIGHT",
                "FRONT_LEFT", "FRONT_RIGHT", "REAR_LEFT", "REAR_RIGHT", "DAMAGE_CLOSE");
        // 컷은 10개인데 코드는 9종이다 — 근접 2컷이 DAMAGE_CLOSE 를 공유한다.
        // 즉 각도 코드만으로는 근접 2컷을 구별할 수 없다. 누락 감지 설계에 영향이 있다.
        assertThat(codes).hasSize(10);
        assertThat(codes.stream().distinct()).hasSize(9);
        assertThat(codes).filteredOn("DAMAGE_CLOSE"::equals).hasSize(2);
        assertThat(guide.shots()).filteredOn(ShootingShot::closeUp).hasSize(2);
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
