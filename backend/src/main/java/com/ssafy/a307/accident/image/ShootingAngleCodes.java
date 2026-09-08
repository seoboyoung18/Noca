package com.ssafy.a307.accident.image;

import com.ssafy.a307.guide.dto.ShootingShot;
import com.ssafy.a307.guide.service.ShootingGuideService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 촬영 가이드가 정한 각도 코드 집합. 값을 여기 복사해 두지 않고
 * {@code shooting-guide.json} 을 읽는 {@link ShootingGuideService} 에서 파생시킨다 —
 * 기획이 그 파일을 고치면 검증도 같이 따라가야 하기 때문이다.
 * <p>
 * 현재 9종: {@code FRONT · FRONT_LEFT · FRONT_RIGHT · LEFT · RIGHT · REAR · REAR_LEFT ·
 * REAR_RIGHT · DAMAGE_CLOSE} (10컷 중 {@code DAMAGE_CLOSE} 가 2번).
 * <p>
 * <b>각도 코드는 저장되지 않는다</b>(answer25 D1). 정본 {@code accident_image} 에 컬럼이 없다.
 * 그래도 발급 요청에서 검증하는 이유는, 오타 난 코드를 조용히 받아 두면 나중에 컬럼이 생겼을 때
 * 이미 잘못된 값으로 화면이 굳기 때문이다.
 */
@Component
public class ShootingAngleCodes {

    private final Set<String> codes;

    public ShootingAngleCodes(ShootingGuideService shootingGuideService) {
        Set<String> collected = new LinkedHashSet<>();
        shootingGuideService.getShootingGuide().shots().stream()
                .map(ShootingShot::angleCode)
                .filter(code -> code != null && !code.isBlank())
                .forEach(collected::add);
        this.codes = Set.copyOf(collected);
    }

    public boolean contains(String angleCode) {
        return angleCode != null && codes.contains(angleCode);
    }

    public Set<String> all() {
        return codes;
    }

    /**
     * 각도 코드를 검증한다. null·빈 값은 통과 — 각도 태그는 선택 입력이다.
     *
     * @throws AccidentImageValidationException 가이드에 없는 코드
     */
    public void validate(String angleCode) {
        if (angleCode == null || angleCode.isBlank()) return;
        if (!contains(angleCode)) {
            throw new AccidentImageValidationException(
                    AccidentImageValidationException.Reason.UNKNOWN_ANGLE_CODE,
                    "촬영 가이드에 없는 각도 코드입니다. (%s)".formatted(angleCode));
        }
    }
}
