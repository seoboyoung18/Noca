package com.ssafy.a307.common.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 업로드된 사고 이미지의 품질 판정에 쓰는 임계값.
 *
 * <p>요구사항 20행의 확인·결정 사항이 "임계값과 최소 해상도는 <b>실험 후 확정</b>" 이므로
 * 값을 코드에 상수로 박지 않고 밖으로 뺐다. 실험이 끝나면 아래 값을 고치면 되고,
 * 실험 중에는 환경변수로 덮어 재빌드 없이 재기동만 하면 된다.
 *
 * <p><b>이 프로젝트의 첫 {@code @ConfigurationProperties} 다.</b> 뒤에 오는 설정 클래스는
 * 이 클래스를 본보기로 삼으면 된다. 규칙은 세 가지다.
 * <ul>
 *   <li>record 로 만들어 <b>불변</b>으로 둔다. setter 를 열면 런타임에 값이 바뀔 수 있는 것처럼 읽힌다</li>
 *   <li>{@code @Validated} + 제약 애너테이션으로 <b>기동 시점에</b> 실패시킨다.
 *       잘못된 설정이 요청 시점까지 살아 있으면 원인을 찾기 어렵다</li>
 *   <li>기본값은 코드가 아니라 {@code application.properties} 가 가진다.
 *       여기에 {@code @DefaultValue} 를 두지 않는 것은 의도적이다 — 프로퍼티가 통째로 빠지면
 *       조용히 코드 기본값으로 도는 대신 {@code minShortEdgePx=0} 이 되어 기동이 실패한다</li>
 * </ul>
 *
 * <p><b>환경변수 이름</b> (둘 다 통한다. {@code ImageQualityPropertiesTest} 로 확인했다)
 * <pre>
 * app.image-quality.enabled                  APP_IMAGEQUALITY_ENABLED
 *                                            APP_IMAGE_QUALITY_ENABLED
 * app.image-quality.min-short-edge-px        APP_IMAGEQUALITY_MINSHORTEDGEPX
 *                                            APP_IMAGE_QUALITY_MIN_SHORT_EDGE_PX
 * app.image-quality.blur-variance-threshold  APP_IMAGEQUALITY_BLURVARIANCETHRESHOLD
 *                                            APP_IMAGE_QUALITY_BLUR_VARIANCE_THRESHOLD
 * </pre>
 * 두 형태를 섞은 {@code APP_IMAGEQUALITY_MIN_SHORT_EDGE_PX} 같은 이름은
 * <b>오류 없이 무시된다.</b> 오버라이드가 안 먹으면 이름부터 의심할 것.
 *
 * <p><b>아직 소비자가 없다.</b> 판정을 수행할 이미지 업로드 API(API 명세서 30·34행)가
 * 구현되지 않아 이 값을 읽어 쓰는 코드가 없다. 값이 먼저 밖에 나와 있어야
 * 임계값 실험과 업로드 구현이 서로를 기다리지 않는다는 판단으로 먼저 만들었다.
 *
 * @see <a href="file:../../../../../../resources/application.properties">application.properties</a>
 */
@Validated
@ConfigurationProperties(prefix = "app.image-quality")
public record ImageQualityProperties(

        /*
         * 품질 판정 수행 여부. 임계값이 실험으로 확정되기 전에는 false 로 둔다 —
         * 근거 없는 숫자로 사용자에게 재촬영을 권하지 않기 위해서다.
         */
        boolean enabled,

        /*
         * 짧은 변이 이 값(px) 미만이면 quality_status='WARN'.
         * 요구사항 20행의 "예: 짧은 변 720px" 에서 온 잠정값이다.
         * 0 이나 음수면 어떤 이미지도 걸리지 않아 판정이 무력화되므로 1 이상만 허용한다.
         */
        @Min(1) int minShortEdgePx,

        /*
         * 라플라시안 분산이 이 값 미만이면 흔들림/초점 불량으로 보고 quality_status='WARN'.
         * 값 자체는 요구사항에 없는 관용적 잠정값이다. 반드시 실험으로 확정해야 한다.
         * 0 이하면 판정이 무력화되므로 양수만 허용한다.
         */
        @Positive double blurVarianceThreshold
) {
}
