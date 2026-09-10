package com.ssafy.a307.accident.config;

import com.ssafy.a307.accident.entity.AccidentImageAsset;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 사고 이미지 업로드·전처리 설정.
 * <p>
 * {@code ImageQualityProperties} 의 세 규칙을 그대로 따른다 — record 로 불변,
 * {@code @Validated} 로 기동 시점 실패, 기본값은 코드가 아니라 {@code application.properties} 가 가진다.
 * {@code @DefaultValue} 를 두지 않는 것은 의도적이다. 프로퍼티가 빠지면 조용히 도는 대신
 * {@code maxCountPerAccident=0} 이 되어 기동이 실패한다.
 *
 * <p><b>장수 상한은 요구사항과 API 명세서가 어긋나 있다.</b>
 * <ul>
 *   <li>요구사항 시트 21행(정본 1순위) — 최대 <b>20장</b></li>
 *   <li>{@code Docs/Api/API 명세서 (바른견적 최신).md} — 사진 상한 <b>10장</b> 확정
 *       (촬영 가이드 8방향 + 근접 2장 기준)</li>
 * </ul>
 * 정본 우선순위에 따라 배포 기본값은 20으로 두되, 값 자체를 코드에 박지 않고 여기로 뺐다.
 * 기획이 10으로 확정하면 프로퍼티만 바꾸면 되고 코드·테스트는 손대지 않는다(answer25 D7).
 *
 * <p><b>환경변수 이름</b> — {@code ImageQualityProperties} 와 같은 규칙이다. 두 형태가 통하고
 * 섞은 이름은 오류 없이 무시된다.
 * <pre>
 * app.accident-image.max-count-per-accident   APP_ACCIDENTIMAGE_MAXCOUNTPERACCIDENT
 *                                             APP_ACCIDENT_IMAGE_MAX_COUNT_PER_ACCIDENT
 * </pre>
 */
@Validated
@ConfigurationProperties(prefix = "app.accident-image")
public record AccidentImageProperties(

        /*
         * 사고 1건에 등록할 수 있는 이미지 장수. 이미 등록된 수 + 요청 수가 이 값을 넘으면 400.
         * 0 이면 아무것도 못 올리므로 1 이상만 허용한다. 상한 100 은 오타 방어용이다 —
         * presigned 발급이 장수만큼 일어나므로 실수로 큰 값이 들어가면 한 요청이 과도하게 커진다.
         */
        @Min(1) @Max(100) int maxCountPerAccident,

        /*
         * 장당 최대 바이트. 정본 DDL 의 ck_aia_size 가 20,971,520 을 강제하므로 그보다 클 수 없다.
         * 크게 두면 S3 에는 올라가고 DB 저장에서 CHECK 위반으로 실패하는 최악의 조합이 된다.
         */
        @Min(1) @Max(AccidentImageAsset.MAX_FILE_SIZE_BYTES) int maxFileSizeBytes,

        /*
         * 분석용 리사이즈본(RESIZED)의 긴 변 상한(px). 요구사항에 수치가 없는 잠정값이다.
         * 종횡비를 유지하며 축소만 하고 확대는 하지 않는다.
         */
        @Min(1) int resizedMaxEdgePx,

        /* 썸네일(THUMBNAIL)의 긴 변 상한(px). 같은 규칙이다. */
        @Min(1) int thumbnailMaxEdgePx,

        /*
         * presigned 업로드 URL 유효시간(분). app.estimate-validation.presigned-url-minutes 와
         * 같은 형태·같은 범위를 쓴다. 하루(1440분)를 넘기면 URL 유출 시 노출 창이 너무 길어진다.
         */
        @Min(1) @Max(1440) int presignedUrlMinutes,

        /*
         * 조회용 presigned GET URL 유효시간(분). 업로드 URL 과 분리한 이유는 성격이 다르기 때문이다 —
         * 업로드는 사용자가 파일을 고르고 올리는 한 번의 동작이고, 조회는 화면이 열려 있는 동안
         * 계속 쓰인다. 짧게 두면 화면을 오래 열어 둔 사용자의 이미지가 깨지고, 길게 두면 URL 이
         * 새 나갔을 때 노출 창이 길어진다. 지금은 업로드와 같은 10분이지만 따로 조정할 수 있어야 한다.
         */
        @Min(1) @Max(1440) int downloadUrlMinutes
) {

    @AssertTrue(message = "thumbnail-max-edge-px must be smaller than resized-max-edge-px")
    public boolean isEdgeOrderValid() {
        return thumbnailMaxEdgePx < resizedMaxEdgePx;
    }

    public Duration presignedUrlValidity() {
        return Duration.ofMinutes(presignedUrlMinutes);
    }

    /** 조회용 GET URL 유효시간. {@link #presignedUrlValidity()} 와 별개 값이다. */
    public Duration downloadUrlValidity() {
        return Duration.ofMinutes(downloadUrlMinutes);
    }
}
