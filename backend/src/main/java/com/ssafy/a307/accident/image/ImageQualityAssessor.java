package com.ssafy.a307.accident.image;

import com.ssafy.a307.accident.entity.ImageQualityStatus;
import com.ssafy.a307.common.config.ImageQualityProperties;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 업로드 이미지 품질 판정. {@link ImageQualityProperties} 의 <b>첫 소비자</b>다 —
 * 그 클래스의 Javadoc 이 말하는 "판정을 수행할 이미지 업로드 API" 가 이 흐름이다.
 *
 * <p>그 클래스의 설계 의도를 그대로 따른다.
 * <ul>
 *   <li>{@code enabled=false} 가 기본값이고, false 면 <b>판정하지 않고</b> {@code PASS} 를 유지한다.
 *       근거 없는 숫자로 사용자에게 재촬영을 권하지 않는다는 뜻이다</li>
 *   <li>{@code minShortEdgePx} 미만이면 {@code WARN} + 사유. 사유는 VARCHAR(100) 이다</li>
 *   <li>블러 판정은 {@link ImageBlurVariancePort} 구현이 있을 때만 한다. 없으면 건너뛴다 —
 *       {@code S15P21A307-120}(AI 담당)이 붙기 전에 흉내내지 않는다</li>
 * </ul>
 *
 * <p>{@code WARN} 이어도 업로드는 성공한다. {@code ck_ai_quality} 에 실패 상태가 없다는 것이
 * 그 뜻이고, 재촬영 권유는 화면(FE {@code S15P21A307-136}) 책임이다.
 */
@Component
public class ImageQualityAssessor {

    private final ImageQualityProperties properties;
    private final Optional<ImageBlurVariancePort> blurPort;

    public ImageQualityAssessor(
            ImageQualityProperties properties, Optional<ImageBlurVariancePort> blurPort) {
        this.properties = properties;
        this.blurPort = blurPort;
    }

    /**
     * @param shortEdgePx EXIF 보정 후 짧은 변
     * @param analysisImage 분석용 리사이즈본 바이트. 블러 판정에만 쓴다
     */
    public Assessment assess(int shortEdgePx, byte[] analysisImage) {
        if (!properties.enabled()) {
            return Assessment.pass();
        }
        if (shortEdgePx < properties.minShortEdgePx()) {
            return Assessment.warn("해상도 부족 — 짧은 변 %dpx (기준 %dpx)"
                    .formatted(shortEdgePx, properties.minShortEdgePx()));
        }
        if (blurPort.isPresent() && analysisImage != null && analysisImage.length > 0) {
            double variance = blurPort.get().variance(analysisImage);
            if (variance < properties.blurVarianceThreshold()) {
                return Assessment.warn("흔들림 의심 — 라플라시안 분산 %.1f (기준 %.1f)"
                        .formatted(variance, properties.blurVarianceThreshold()));
            }
        }
        return Assessment.pass();
    }

    /** 블러 판정이 실제로 연결되어 있는지. 문서·응답에서 미검증 범위를 구분하는 데 쓴다. */
    public boolean blurAssessmentConnected() {
        return blurPort.isPresent();
    }

    public record Assessment(ImageQualityStatus status, String reason) {

        static Assessment pass() {
            return new Assessment(ImageQualityStatus.PASS, null);
        }

        static Assessment warn(String reason) {
            return new Assessment(ImageQualityStatus.WARN, reason);
        }
    }
}
