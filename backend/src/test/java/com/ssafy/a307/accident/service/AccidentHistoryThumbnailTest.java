package com.ssafy.a307.accident.service;

import com.ssafy.a307.accident.dto.AccidentCreateRequest;
import com.ssafy.a307.accident.dto.AccidentPageResponse;
import com.ssafy.a307.accident.dto.AccidentSummaryResponse;
import com.ssafy.a307.accident.image.AccidentImageStoragePort;
import com.ssafy.a307.vehicle.dto.VehicleCreateRequest;
import com.ssafy.a307.vehicle.dto.VehicleResponse;
import com.ssafy.a307.vehicle.service.VehicleService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * 이력 목록의 <b>썸네일 조회 URL</b>(Task 225).
 *
 * <p>{@code AccidentServiceTest} 와 나눈 이유는 저장소 어댑터가 필요하기 때문이다. 테스트 환경은
 * 버킷이 비어 있어 어댑터 빈이 뜨지 않고, 그 상태에서는 URL 이 항상 {@code null} 이라
 * "URL 이 실린다" 를 증명할 수 없다. 여기서만 포트를 대역으로 채운다.
 *
 * <p><b>실제 S3 를 부르지 않는다.</b> presigned 서명은 순수 계산이라 목으로 대신해도 검증하려는
 * 것 — 어떤 키에 서명을 요청하는가, 응답에 어떻게 실리는가 — 이 그대로 남는다.
 */
@SpringBootTest
@Transactional
@DisplayName("사고 이력 목록 썸네일")
class AccidentHistoryThumbnailTest {

    private static final long ME = 1L;
    private static final Instant EXPIRES = Instant.parse("2026-09-10T09:40:00Z");

    @Autowired
    private AccidentService accidentService;
    @Autowired
    private VehicleService vehicleService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private AccidentImageStoragePort storagePort;

    private long avante;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update(
                "insert into member (member_id, provider, provider_user_id, nickname)"
                        + " values (?, 'KAKAO', 'kakao-me', 'tester')", ME);
        jdbcTemplate.update(
                "insert into vehicle_model (manufacturer, model_name, vehicle_type, car_class, is_active)"
                        + " values ('현대', '아반떼', 'SEDAN', 'Mid-size', true)");
        avante = jdbcTemplate.queryForObject(
                "select model_id from vehicle_model where model_name = '아반떼'", Long.class);
    }

    @Test
    @DisplayName("썸네일 URL 과 만료 시각이 응답에 실린다")
    void exposesThumbnailUrlAndExpiry() {
        long accidentId = openAccident();
        long imageId = insertImage(accidentId, "front.jpg");
        insertAsset(imageId, "THUMBNAIL", "accidents/1/images/1/thumbnail.jpg");
        flushAndClear();
        givenPresign("https://service.example.com/thumb.jpg?X-Amz-Signature=abc");

        AccidentSummaryResponse summary = onlyAccident();

        assertThat(summary.thumbnailUrl())
                .isEqualTo("https://service.example.com/thumb.jpg?X-Amz-Signature=abc");
        assertThat(summary.thumbnailExpiresAt()).isEqualTo(EXPIRES);
    }

    @Test
    @DisplayName("가장 먼저 올린 이미지의 THUMBNAIL 키로 서명한다")
    void signsThumbnailOfFirstImage() {
        long accidentId = openAccident();
        long first = insertImage(accidentId, "front.jpg");
        long second = insertImage(accidentId, "rear.jpg");
        insertAsset(first, "THUMBNAIL", "accidents/1/images/" + first + "/thumbnail.jpg");
        insertAsset(second, "THUMBNAIL", "accidents/1/images/" + second + "/thumbnail.jpg");
        flushAndClear();
        givenPresign("https://service.example.com/thumb.jpg");

        onlyAccident();

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        then(storagePort).should().createPresignedDownloadUrl(key.capture(), any(Duration.class));
        assertThat(key.getValue()).contains("/images/" + first + "/");
    }

    @Test
    @DisplayName("ORIGINAL·RESIZED 만 있으면 서명하지 않는다 — 원본은 목록에 나가지 않는다")
    void neverSignsOriginalOrResized() {
        long accidentId = openAccident();
        long imageId = insertImage(accidentId, "front.jpg");
        insertAsset(imageId, "ORIGINAL", "accidents/1/images/1/original.jpg");
        insertAsset(imageId, "RESIZED", "accidents/1/images/1/resized.jpg");
        flushAndClear();

        AccidentSummaryResponse summary = onlyAccident();

        assertThat(summary.thumbnailUrl()).isNull();
        assertThat(summary.imageCount()).isEqualTo(1);
        then(storagePort).should(never()).createPresignedDownloadUrl(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("서명이 실패하면 그 건만 썸네일이 비고 나머지 필드는 그대로다")
    void degradesToNullOnSigningFailure() {
        long accidentId = openAccident();
        long imageId = insertImage(accidentId, "front.jpg");
        insertAsset(imageId, "THUMBNAIL", "accidents/1/images/1/thumbnail.jpg");
        flushAndClear();
        given(storagePort.createPresignedDownloadUrl(anyString(), any(Duration.class)))
                .willThrow(new IllegalStateException("서명 실패"));

        AccidentSummaryResponse summary = onlyAccident();

        assertThat(summary.thumbnailUrl()).isNull();
        assertThat(summary.thumbnailExpiresAt()).isNull();
        assertThat(summary.accidentId()).isEqualTo(accidentId);
        assertThat(summary.imageCount()).isEqualTo(1);
        assertThat(summary.manufacturer()).isEqualTo("현대");
    }

    @Test
    @DisplayName("사고가 여러 건이어도 각 건의 첫 이미지만 서명한다 — 이미지 수에 비례하지 않는다")
    void signsOncePerAccident() {
        VehicleResponse vehicle = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
        for (int i = 0; i < 3; i++) {
            long accidentId = accidentService
                    .create(ME, new AccidentCreateRequest(vehicle.vehicleId())).accidentId();
            for (int j = 0; j < 4; j++) {
                long imageId = insertImage(accidentId, "shot" + j + ".jpg");
                insertAsset(imageId, "THUMBNAIL", "accidents/" + accidentId + "/images/" + imageId + "/thumbnail.jpg");
            }
        }
        flushAndClear();
        givenPresign("https://service.example.com/thumb.jpg");

        AccidentPageResponse paged = accidentService.findMinePaged(ME, 0, 20, false);

        assertThat(paged.accidents()).hasSize(3);
        assertThat(paged.accidents()).allSatisfy(summary -> {
            assertThat(summary.imageCount()).isEqualTo(4);
            assertThat(summary.thumbnailUrl()).isNotNull();
        });
        // 이미지 12장이지만 서명은 사고당 한 번, 3번뿐이다.
        then(storagePort).should(org.mockito.Mockito.times(3))
                .createPresignedDownloadUrl(anyString(), any(Duration.class));
    }

    private void givenPresign(String url) {
        given(storagePort.createPresignedDownloadUrl(anyString(), any(Duration.class)))
                .willReturn(new AccidentImageStoragePort.PresignedDownload(URI.create(url), EXPIRES));
    }

    private long openAccident() {
        VehicleResponse vehicle = vehicleService.create(ME, new VehicleCreateRequest(avante, 2020));
        long accidentId = accidentService
                .create(ME, new AccidentCreateRequest(vehicle.vehicleId())).accidentId();
        flushAndClear();
        return accidentId;
    }

    private AccidentSummaryResponse onlyAccident() {
        List<AccidentSummaryResponse> accidents = accidentService.findMinePaged(ME, 0, 20, false).accidents();
        assertThat(accidents).hasSize(1);
        return accidents.getFirst();
    }

    private long insertImage(long accidentId, String filename) {
        jdbcTemplate.update(
                "insert into accident_image (accident_id, original_filename) values (?, ?)",
                accidentId, filename);
        return jdbcTemplate.queryForObject(
                "select max(image_id) from accident_image where accident_id = ?",
                Long.class, accidentId);
    }

    private void insertAsset(long imageId, String variant, String s3Key) {
        jdbcTemplate.update(
                "insert into accident_image_asset (image_id, variant, s3_key, width, height, file_size)"
                        + " values (?, ?, ?, 320, 240, 14802)",
                imageId, variant, s3Key);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
