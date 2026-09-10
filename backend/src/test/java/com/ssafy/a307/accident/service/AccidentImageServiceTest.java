package com.ssafy.a307.accident.service;

import com.ssafy.a307.accident.config.AccidentImageProperties;
import com.ssafy.a307.accident.dto.AccidentImageListResponse;
import com.ssafy.a307.accident.dto.AccidentImageResponse;
import com.ssafy.a307.accident.dto.AccidentImageResultResponse;
import com.ssafy.a307.accident.dto.ImageProcessingStatus;
import com.ssafy.a307.accident.dto.ImageUploadCompleteItem;
import com.ssafy.a307.accident.dto.ImageUploadCompleteRequest;
import com.ssafy.a307.accident.dto.ImageUploadCompleteResponse;
import com.ssafy.a307.accident.dto.ImageUploadState;
import com.ssafy.a307.accident.dto.ImageUploadUrlItem;
import com.ssafy.a307.accident.dto.ImageUploadUrlRequest;
import com.ssafy.a307.accident.dto.ImageUploadUrlResponse;
import com.ssafy.a307.accident.dto.IssuedUploadUrl;
import com.ssafy.a307.accident.entity.ImageQualityStatus;
import com.ssafy.a307.accident.entity.ImageVariant;
import com.ssafy.a307.accident.image.AccidentImageKeys;
import com.ssafy.a307.accident.image.AccidentImageStoragePort;
import com.ssafy.a307.accident.image.AccidentImageValidationException;
import com.ssafy.a307.accident.image.AccidentImageValidationException.Reason;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 발급 → PUT → 완료 통보 흐름의 통합 검증.
 * <p>
 * <b>S3 어댑터가 없으므로 저장소는 테스트 더블이다.</b> 여기서 통과한 것은 "서버 로직이 맞다" 까지이고,
 * 실제 S3 서명·권한·content-length-range 강제는 어댑터가 붙은 뒤 다시 확인해야 한다
 * (answer25 3장의 재검증 목록).
 * <p>
 * 클래스에 {@code @Transactional} 을 붙이지 않는다. 완료 처리가 {@code REQUIRES_NEW} 로 도는데
 * 테스트가 트랜잭션을 쥐고 있으면 그 안쪽 트랜잭션이 시드 데이터를 보지 못한다. 대신
 * 높은 ID 대역을 쓰고 {@code @AfterEach} 에서 직접 지운다.
 */
@SpringBootTest
@Import(AccidentImageServiceTest.FakeStorageConfig.class)
@DisplayName("사고 이미지 업로드")
class AccidentImageServiceTest {

    private static final long MEMBER_ID = 91_001L;
    private static final long OTHER_MEMBER_ID = 91_002L;
    private static final long MODEL_ID = 91_003L;
    private static final long VEHICLE_ID = 91_004L;
    private static final long OTHER_VEHICLE_ID = 91_005L;
    private static final long ACCIDENT_ID = 91_006L;
    private static final long OTHER_ACCIDENT_ID = 91_007L;
    private static final long MISSING_ACCIDENT_ID = 91_999L;

    @TestConfiguration
    static class FakeStorageConfig {

        @Bean
        InMemoryImageStorage inMemoryImageStorage() {
            return new InMemoryImageStorage();
        }
    }

    /** presigned·읽기·쓰기·삭제를 메모리에서 흉내내는 포트 더블. */
    static class InMemoryImageStorage implements AccidentImageStoragePort {

        final Map<String, byte[]> objects = new LinkedHashMap<>();
        final Map<String, String> contentTypes = new LinkedHashMap<>();
        final List<UploadUrlRequest> issued = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        final List<String> downloadSigned = new ArrayList<>();
        boolean failDownloadSigning;

        void reset() {
            objects.clear();
            contentTypes.clear();
            issued.clear();
            deleted.clear();
            downloadSigned.clear();
            failDownloadSigning = false;
        }

        void put(String key, byte[] content, String contentType) {
            objects.put(key, content);
            contentTypes.put(key, contentType);
        }

        @Override
        public PresignedUpload createPresignedUploadUrl(UploadUrlRequest request) {
            issued.add(request);
            return new PresignedUpload(
                    URI.create("https://storage.test/" + request.storageKey() + "?signed=1"),
                    Instant.now().plus(request.validity()),
                    Map.of("Content-Type", request.contentType()));
        }

        @Override
        public StoredObject head(String storageKey) {
            byte[] content = objects.get(storageKey);
            if (content == null) throw new IllegalStateException("no such object: " + storageKey);
            return new StoredObject(
                    storageKey, content.length,
                    contentTypes.getOrDefault(storageKey, "application/octet-stream"));
        }

        @Override
        public byte[] read(String storageKey) {
            byte[] content = objects.get(storageKey);
            if (content == null) throw new IllegalStateException("no such object: " + storageKey);
            return content.clone();
        }

        /**
         * 조회용 서명. 실제 어댑터와 같은 규칙을 지킨다 — <b>{@code ORIGINAL} 은 거절</b>한다.
         * 더블이 더 관대하면 원본이 새 나가는 회귀를 테스트가 못 잡는다.
         */
        @Override
        public PresignedDownload createPresignedDownloadUrl(String storageKey, Duration validity) {
            if (failDownloadSigning) throw new IllegalStateException("presign failure (test)");
            if (storageKey.contains("/" + ImageVariant.ORIGINAL.objectName() + ".")) {
                throw new IllegalArgumentException("ORIGINAL 은 조회 URL 을 발급하지 않는다");
            }
            downloadSigned.add(storageKey);
            return new PresignedDownload(
                    URI.create("https://storage.test/" + storageKey + "?download=1"),
                    Instant.now().plus(validity));
        }

        @Override
        public StoredObject store(StoreImage request) {
            put(request.storageKey(), request.content(), request.contentType());
            return new StoredObject(
                    request.storageKey(), request.content().length, request.contentType());
        }

        @Override
        public void delete(String storageKey) {
            deleted.add(storageKey);
            objects.remove(storageKey);
            contentTypes.remove(storageKey);
        }
    }

    @Autowired
    private AccidentImageService service;
    @Autowired
    private AccidentImageProperties properties;
    @Autowired
    private InMemoryImageStorage storage;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void setUp() {
        storage.reset();
        cleanUp();
        jdbcTemplate.update("""
                insert into member(member_id, provider, provider_user_id, nickname)
                values (?, 'KAKAO', 'image-owner', '이미지주인'), (?, 'KAKAO', 'image-other', '남')
                """, MEMBER_ID, OTHER_MEMBER_ID);
        jdbcTemplate.update("""
                insert into vehicle_model(model_id, manufacturer, model_name, vehicle_type, car_class, is_active)
                values (?, '이미지제조사', '이미지차량', 'SEDAN', 'Compact', true)
                """, MODEL_ID);
        jdbcTemplate.update("""
                insert into vehicle(vehicle_id, member_id, model_id, model_year)
                values (?, ?, ?, 2020), (?, ?, ?, 2020)
                """, VEHICLE_ID, MEMBER_ID, MODEL_ID, OTHER_VEHICLE_ID, OTHER_MEMBER_ID, MODEL_ID);
        insertAccident(ACCIDENT_ID, VEHICLE_ID);
        insertAccident(OTHER_ACCIDENT_ID, OTHER_VEHICLE_ID);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    // ------------------------------------------------------------------ 발급

    @Nested
    @DisplayName("업로드 URL 발급")
    class Issue {

        @Test
        @DisplayName("파일별 URL·imageId·s3Key 를 주고 accident_image 행을 예약한다")
        void issues() {
            ImageUploadUrlResponse response = service.issueUploadUrls(MEMBER_ID, ACCIDENT_ID,
                    request(file("front.jpg", "image/jpeg", 1024, "FRONT"),
                            file("rear.png", "image/png", 2048, null)));

            assertThat(response.issuedCount()).isEqualTo(2);
            assertThat(response.maxCountPerAccident()).isEqualTo(properties.maxCountPerAccident());
            assertThat(response.remainingSlots()).isEqualTo(properties.maxCountPerAccident() - 2);

            IssuedUploadUrl first = response.files().get(0);
            assertThat(first.imageId()).isNotNull();
            assertThat(first.originalFilename()).isEqualTo("front.jpg");
            assertThat(first.angleCode()).isEqualTo("FRONT");
            assertThat(first.uploadMethod()).isEqualTo("PUT");
            assertThat(first.uploadUrl()).contains(first.s3Key());
            assertThat(first.requiredHeaders()).containsEntry("Content-Type", "image/jpeg");
            assertThat(first.expiresAt()).isAfter(Instant.now());

            assertThat(rows()).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("""
                    select quality_status from accident_image where image_id = ?
                    """, String.class, first.imageId())).isEqualTo("PASS");
        }

        @Test
        @DisplayName("발급된 s3Key 가 키 규칙과 같다 — 키는 서버가 만든다")
        void keyFollowsRule() {
            ImageUploadUrlResponse response = service.issueUploadUrls(MEMBER_ID, ACCIDENT_ID,
                    request(file("front.jpeg", "image/jpeg", 1024, null),
                            file("side.png", "image/png", 1024, null)));

            IssuedUploadUrl jpeg = response.files().get(0);
            IssuedUploadUrl png = response.files().get(1);

            // jpeg 확장자로 올려도 키는 jpg 로 통일한다
            assertThat(jpeg.s3Key()).isEqualTo(AccidentImageKeys.key(
                    ACCIDENT_ID, jpeg.imageId(), ImageVariant.ORIGINAL, "jpg"));
            assertThat(png.s3Key()).isEqualTo(AccidentImageKeys.key(
                    ACCIDENT_ID, png.imageId(), ImageVariant.ORIGINAL, "png"));
            assertThat(jpeg.s3Key()).doesNotContain("front");
        }

        /**
         * <b>상한이 아니라 신고 크기가 실린다.</b> 어댑터가 그 값을 presigned 에 정확값으로
         * 서명하므로, 상한(20MB)을 넘기면 모든 업로드가 정확히 20MB 여야 하는 셈이 된다.
         * 신고값은 바로 앞의 1단 검증이 이미 상한과 대조한 뒤다.
         */
        @Test
        @DisplayName("presigned 조건에 Content-Type·신고 크기·프로퍼티 유효시간이 실린다")
        void presignedCarriesConstraints() {
            service.issueUploadUrls(MEMBER_ID, ACCIDENT_ID,
                    request(file("front.jpg", "image/jpeg", 1024, null)));

            AccidentImageStoragePort.UploadUrlRequest issued = storage.issued.get(0);
            assertThat(issued.contentType()).isEqualTo("image/jpeg");
            assertThat(issued.contentLength()).isEqualTo(1024L);
            assertThat(issued.contentLength()).isNotEqualTo(properties.maxFileSizeBytes());
            assertThat(issued.validity())
                    .isEqualTo(Duration.ofMinutes(properties.presignedUrlMinutes()));
        }

        @Test
        @DisplayName("남의 사고·없는 사고는 모두 404 다")
        void notFound() {
            assertThat(errorOf(() -> service.issueUploadUrls(MEMBER_ID, OTHER_ACCIDENT_ID,
                    request(file("front.jpg", "image/jpeg", 1024, null)))))
                    .isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(errorOf(() -> service.issueUploadUrls(MEMBER_ID, MISSING_ACCIDENT_ID,
                    request(file("front.jpg", "image/jpeg", 1024, null)))))
                    .isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(rows()).isZero();
        }

        @Test
        @DisplayName("누적 장수 상한을 넘으면 400 이고 행이 하나도 생기지 않는다")
        void countLimit() {
            int max = properties.maxCountPerAccident();
            service.issueUploadUrls(MEMBER_ID, ACCIDENT_ID, request(files(max - 1)));
            assertThat(rows()).isEqualTo(max - 1);

            AccidentImageValidationException e = catchThrowableOfType(
                    AccidentImageValidationException.class,
                    () -> service.issueUploadUrls(MEMBER_ID, ACCIDENT_ID, request(files(2))));

            // 사유를 BusinessException 으로 뭉개지 않는다 — FE 가 error.code 로 구분한다.
            assertThat(e.reason()).isEqualTo(Reason.TOO_MANY_IMAGES);
            assertThat(e).hasMessageContaining(String.valueOf(max));
            assertThat(rows()).isEqualTo(max - 1);
        }

        @Test
        @DisplayName("상한과 정확히 같은 장수는 통과한다")
        void countBoundary() {
            service.issueUploadUrls(MEMBER_ID, ACCIDENT_ID,
                    request(files(properties.maxCountPerAccident())));

            assertThat(rows()).isEqualTo(properties.maxCountPerAccident());
        }

        @Test
        @DisplayName("HEIC 은 서버 변환이 없어 400 과 클라이언트 변환 안내를 준다")
        void heic() {
            AccidentImageValidationException e = catchThrowableOfType(
                    AccidentImageValidationException.class,
                    () -> service.issueUploadUrls(MEMBER_ID, ACCIDENT_ID,
                            request(file("iphone.heic", "image/heic", 1024, null))));

            assertThat(e.reason()).isEqualTo(Reason.SERVER_CONVERSION_UNSUPPORTED);
            assertThat(e).hasMessageContaining("JPG");
            assertThat(rows()).isZero();
            assertThat(storage.issued).as("S3 URL 이 발급되기 전에 걸려야 한다").isEmpty();
        }

        @Test
        @DisplayName("촬영 가이드에 없는 각도 코드는 400 이다")
        void unknownAngleCode() {
            AccidentImageValidationException e = catchThrowableOfType(
                    AccidentImageValidationException.class,
                    () -> service.issueUploadUrls(MEMBER_ID, ACCIDENT_ID,
                            request(file("front.jpg", "image/jpeg", 1024, "TOP_DOWN"))));

            assertThat(e.reason()).isEqualTo(Reason.UNKNOWN_ANGLE_CODE);
            assertThat(e).hasMessageContaining("TOP_DOWN");
            assertThat(rows()).as("각도가 틀리면 행도 남지 않는다").isZero();
        }

        @Test
        @DisplayName("한 파일이 걸리면 나머지도 발급되지 않는다 — 검증을 먼저 전부 한다")
        void allOrNothing() {
            assertThatThrownBy(() -> service.issueUploadUrls(MEMBER_ID, ACCIDENT_ID,
                    request(file("ok.jpg", "image/jpeg", 1024, null),
                            file("bad.gif", "image/gif", 1024, null))))
                    .isInstanceOf(AccidentImageValidationException.class);

            assertThat(rows()).isZero();
            assertThat(storage.issued).isEmpty();
        }
    }

    // ------------------------------------------------------------- 완료 통보

    @Nested
    @DisplayName("완료 통보")
    class Complete {

        @Test
        @DisplayName("asset 3행을 저장하고 품질을 판정한다 — 응답에는 ORIGINAL 을 싣지 않는다")
        void completes() {
            IssuedUploadUrl issued = issueOne("front.jpg", "image/jpeg");
            byte[] uploaded = upload(issued, 400, 300);

            ImageUploadCompleteResponse response = service.complete(MEMBER_ID, ACCIDENT_ID,
                    new ImageUploadCompleteRequest(List.of(
                            new ImageUploadCompleteItem(issued.imageId(), (long) uploaded.length))));

            assertThat(response.requested()).isEqualTo(1);
            assertThat(response.succeeded()).isEqualTo(1);
            assertThat(response.failed()).isZero();

            AccidentImageResultResponse result = response.results().get(0);
            assertThat(result.status()).isEqualTo(ImageProcessingStatus.COMPLETED);
            assertThat(result.qualityStatus()).isEqualTo(ImageQualityStatus.PASS);
            assertThat(result.qualityReason()).isNull();
            // 원본에는 EXIF(촬영 위치·기기)가 남아 있어 화면으로 내보내지 않는다.
            assertThat(result.assets()).extracting("variant")
                    .containsExactlyInAnyOrder(ImageVariant.RESIZED, ImageVariant.THUMBNAIL)
                    .doesNotContain(ImageVariant.ORIGINAL);
            // 키는 더 이상 나가지 않는다. 대신 브라우저가 바로 쓸 수 있는 조회 URL 이 온다.
            assertThat(result.assets()).allSatisfy(asset -> {
                assertThat(asset.url()).as("조회 URL 이 없으면 화면이 이미지를 띄울 수 없다").isNotBlank();
                assertThat(asset.expiresAt()).isNotNull();
            });
            assertThat(result.assets()).extracting("url")
                    .as("원본 키가 URL 로 새 나가면 안 된다")
                    .noneMatch(url -> String.valueOf(url).contains("/original."));
            assertThat(storage.downloadSigned)
                    .as("서명 대상은 파생본뿐이다")
                    .containsExactlyInAnyOrder(
                            AccidentImageKeys.key(ACCIDENT_ID, issued.imageId(), ImageVariant.RESIZED, "jpg"),
                            AccidentImageKeys.key(ACCIDENT_ID, issued.imageId(), ImageVariant.THUMBNAIL, "jpg"));

            // 저장은 그대로 3행이다 — 분석 파이프라인이 원본을 읽어야 한다.
            assertThat(variants(issued.imageId()))
                    .containsExactlyInAnyOrder("ORIGINAL", "RESIZED", "THUMBNAIL");
            assertThat(storage.objects).containsKeys(
                    issued.s3Key(),
                    AccidentImageKeys.key(ACCIDENT_ID, issued.imageId(), ImageVariant.RESIZED, "jpg"),
                    AccidentImageKeys.key(ACCIDENT_ID, issued.imageId(), ImageVariant.THUMBNAIL, "jpg"));
        }

        @Test
        @DisplayName("원본 asset 의 크기·해상도는 실제 오브젝트에서 온다")
        void originalMetadata() {
            IssuedUploadUrl issued = issueOne("front.jpg", "image/jpeg");
            byte[] uploaded = upload(issued, 400, 300);

            service.complete(MEMBER_ID, ACCIDENT_ID, completeRequest(issued.imageId()));

            Map<String, Object> original = jdbcTemplate.queryForMap("""
                    select width, height, file_size from accident_image_asset
                    where image_id = ? and variant = 'ORIGINAL'
                    """, issued.imageId());
            assertThat(((Number) original.get("width")).intValue()).isEqualTo(400);
            assertThat(((Number) original.get("height")).intValue()).isEqualTo(300);
            assertThat(((Number) original.get("file_size")).intValue()).isEqualTo(uploaded.length);
        }

        @Test
        @DisplayName("EXIF 회전이 있으면 표시 크기로 저장한다")
        void orientationApplied() {
            IssuedUploadUrl issued = issueOne("rotated.jpg", "image/jpeg");
            storage.put(issued.s3Key(),
                    com.ssafy.a307.accident.image.TestImages.jpegWithOrientation(400, 300, 6),
                    "image/jpeg");

            service.complete(MEMBER_ID, ACCIDENT_ID, completeRequest(issued.imageId()));

            Map<String, Object> original = jdbcTemplate.queryForMap("""
                    select width, height from accident_image_asset
                    where image_id = ? and variant = 'ORIGINAL'
                    """, issued.imageId());
            assertThat(((Number) original.get("width")).intValue()).isEqualTo(300);
            assertThat(((Number) original.get("height")).intValue()).isEqualTo(400);
        }

        @Test
        @DisplayName("이미 완료된 이미지를 다시 통보하면 재처리하지 않는다 — uk_aia 를 위반하지 않는다")
        void idempotent() {
            IssuedUploadUrl issued = issueOne("front.jpg", "image/jpeg");
            upload(issued, 200, 150);
            service.complete(MEMBER_ID, ACCIDENT_ID, completeRequest(issued.imageId()));

            ImageUploadCompleteResponse again =
                    service.complete(MEMBER_ID, ACCIDENT_ID, completeRequest(issued.imageId()));

            assertThat(again.results().get(0).status())
                    .isEqualTo(ImageProcessingStatus.ALREADY_COMPLETED);
            assertThat(again.failed()).isZero();
            assertThat(variants(issued.imageId())).hasSize(3);
        }

        @Test
        @DisplayName("신고 크기와 실제 크기가 다르면 그 이미지만 실패하고 오브젝트를 정리한다")
        void sizeMismatchCleansUp() {
            IssuedUploadUrl issued = issueOne("front.jpg", "image/jpeg");
            upload(issued, 200, 150);

            ImageUploadCompleteResponse response = service.complete(MEMBER_ID, ACCIDENT_ID,
                    new ImageUploadCompleteRequest(List.of(
                            new ImageUploadCompleteItem(issued.imageId(), 999_999L))));

            AccidentImageResultResponse result = response.results().get(0);
            assertThat(result.status()).isEqualTo(ImageProcessingStatus.FAILED);
            assertThat(result.failureCode()).isEqualTo(Reason.SIZE_MISMATCH.name());
            assertThat(result.failureMessage()).contains("999999");
            assertThat(response.failed()).isEqualTo(1);

            assertThat(variants(issued.imageId())).isEmpty();
            assertThat(storage.deleted).contains(issued.s3Key());
            assertThat(storage.objects).doesNotContainKey(issued.s3Key());
            // 행은 남는다 — 같은 imageId 로 다시 올릴 수 있어야 한다
            assertThat(rows()).isEqualTo(1);
        }

        @Test
        @DisplayName("시그니처가 확장자와 다르면 실패한다 — 확장자만 바꿔 올린 파일이 걸린다")
        void signatureMismatch() {
            IssuedUploadUrl issued = issueOne("front.jpg", "image/jpeg");
            storage.put(issued.s3Key(),
                    com.ssafy.a307.accident.image.TestImages.png(20, 20), "image/jpeg");

            AccidentImageResultResponse result = service
                    .complete(MEMBER_ID, ACCIDENT_ID, completeRequest(issued.imageId()))
                    .results().get(0);

            assertThat(result.status()).isEqualTo(ImageProcessingStatus.FAILED);
            assertThat(result.failureCode()).isEqualTo(Reason.SIGNATURE_MISMATCH.name());
            assertThat(variants(issued.imageId())).isEmpty();
        }

        @Test
        @DisplayName("업로드되지 않은 이미지를 통보하면 그 이미지만 실패한다")
        void notUploaded() {
            IssuedUploadUrl issued = issueOne("front.jpg", "image/jpeg");

            AccidentImageResultResponse result = service
                    .complete(MEMBER_ID, ACCIDENT_ID, completeRequest(issued.imageId()))
                    .results().get(0);

            assertThat(result.status()).isEqualTo(ImageProcessingStatus.FAILED);
            assertThat(result.failureCode()).isEqualTo(Reason.MISSING_FILE.name());
        }

        @Test
        @DisplayName("일부가 실패해도 나머지는 저장된다 — 전체 롤백하지 않는다")
        void partialFailure() {
            ImageUploadUrlResponse issued = service.issueUploadUrls(MEMBER_ID, ACCIDENT_ID,
                    request(file("good.jpg", "image/jpeg", 1024, null),
                            file("missing.jpg", "image/jpeg", 1024, null),
                            file("good2.jpg", "image/jpeg", 1024, null)));
            upload(issued.files().get(0), 200, 150);
            upload(issued.files().get(2), 200, 150);

            ImageUploadCompleteResponse response = service.complete(MEMBER_ID, ACCIDENT_ID,
                    new ImageUploadCompleteRequest(issued.files().stream()
                            .map(file -> new ImageUploadCompleteItem(file.imageId()))
                            .toList()));

            assertThat(response.succeeded()).isEqualTo(2);
            assertThat(response.failed()).isEqualTo(1);
            assertThat(variants(issued.files().get(0).imageId())).hasSize(3);
            assertThat(variants(issued.files().get(1).imageId())).isEmpty();
            assertThat(variants(issued.files().get(2).imageId())).hasSize(3);
        }

        @Test
        @DisplayName("실패한 파일만 다시 통보해 재시도할 수 있다")
        void retryFailedOnly() {
            IssuedUploadUrl issued = issueOne("front.jpg", "image/jpeg");
            service.complete(MEMBER_ID, ACCIDENT_ID, completeRequest(issued.imageId()));
            assertThat(variants(issued.imageId())).isEmpty();

            upload(issued, 200, 150);
            ImageUploadCompleteResponse retry =
                    service.complete(MEMBER_ID, ACCIDENT_ID, completeRequest(issued.imageId()));

            assertThat(retry.succeeded()).isEqualTo(1);
            assertThat(variants(issued.imageId())).hasSize(3);
        }

        @Test
        @DisplayName("남의 이미지·없는 이미지를 통보하면 404 다")
        void notFound() {
            IssuedUploadUrl mine = issueOne("front.jpg", "image/jpeg");

            assertThat(errorOf(() -> service.complete(
                    OTHER_MEMBER_ID, ACCIDENT_ID, completeRequest(mine.imageId()))))
                    .isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(errorOf(() -> service.complete(
                    MEMBER_ID, ACCIDENT_ID, completeRequest(88_888L))))
                    .isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("같은 imageId 를 중복으로 통보하면 400 이다")
        void duplicateImageId() {
            IssuedUploadUrl issued = issueOne("front.jpg", "image/jpeg");
            upload(issued, 200, 150);

            BusinessException e = catchThrowableOfType(BusinessException.class,
                    () -> service.complete(MEMBER_ID, ACCIDENT_ID, new ImageUploadCompleteRequest(
                            List.of(new ImageUploadCompleteItem(issued.imageId()),
                                    new ImageUploadCompleteItem(issued.imageId())))));

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        }
    }

    // -------------------------------------------------------- 상태 조회·삭제

    @Nested
    @DisplayName("상태 조회와 삭제")
    class StatusAndDelete {

        @Test
        @DisplayName("완료 통보를 받지 못한 파일을 PENDING 으로 알려준다")
        void listSeparatesPending() {
            ImageUploadUrlResponse issued = service.issueUploadUrls(MEMBER_ID, ACCIDENT_ID,
                    request(file("a.jpg", "image/jpeg", 1024, null),
                            file("b.jpg", "image/jpeg", 1024, null)));
            upload(issued.files().get(0), 200, 150);
            service.complete(MEMBER_ID, ACCIDENT_ID, completeRequest(issued.files().get(0).imageId()));

            AccidentImageListResponse list = service.list(MEMBER_ID, ACCIDENT_ID);

            assertThat(list.total()).isEqualTo(2);
            assertThat(list.completed()).isEqualTo(1);
            assertThat(list.pending()).isEqualTo(1);
            assertThat(list.remainingSlots()).isEqualTo(properties.maxCountPerAccident() - 2);
            assertThat(list.images()).extracting("uploadState")
                    .containsExactly(ImageUploadState.COMPLETED, ImageUploadState.PENDING);
            // 저장은 3행이지만 응답에는 ORIGINAL 을 빼고 2행만 실린다.
            assertThat(list.images().get(0).assets()).hasSize(2);
            assertThat(list.images().get(0).assets()).extracting("variant")
                    .containsExactlyInAnyOrder(ImageVariant.RESIZED, ImageVariant.THUMBNAIL);
            assertThat(list.images().get(1).assets()).isEmpty();
        }

        @Test
        @DisplayName("남의 사고 상태는 404 다")
        void listNotFound() {
            assertThat(errorOf(() -> service.list(MEMBER_ID, OTHER_ACCIDENT_ID)))
                    .isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("삭제하면 asset 과 저장소 오브젝트가 함께 정리된다")
        void deletesAssetsAndObjects() {
            IssuedUploadUrl issued = issueOne("front.jpg", "image/jpeg");
            upload(issued, 200, 150);
            service.complete(MEMBER_ID, ACCIDENT_ID, completeRequest(issued.imageId()));

            service.delete(MEMBER_ID, ACCIDENT_ID, issued.imageId());

            assertThat(rows()).isZero();
            assertThat(variants(issued.imageId())).isEmpty();
            assertThat(storage.objects).isEmpty();
            assertThat(storage.deleted).contains(issued.s3Key());
        }

        @Test
        @DisplayName("완료 통보 전 취소된 업로드의 원본도 지운다")
        void deletesSpeculativeOriginal() {
            IssuedUploadUrl issued = issueOne("front.jpg", "image/jpeg");
            upload(issued, 200, 150);

            service.delete(MEMBER_ID, ACCIDENT_ID, issued.imageId());

            assertThat(storage.deleted).contains(issued.s3Key());
            assertThat(storage.objects).isEmpty();
        }

        @Test
        @DisplayName("남의 이미지는 404 다")
        void deleteNotFound() {
            IssuedUploadUrl issued = issueOne("front.jpg", "image/jpeg");

            assertThat(errorOf(() -> service.delete(OTHER_MEMBER_ID, ACCIDENT_ID, issued.imageId())))
                    .isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(rows()).isEqualTo(1);
        }

        @Test
        @DisplayName("사고를 지우면 이미지와 asset 이 FK CASCADE 로 정리된다")
        void accidentCascade() {
            IssuedUploadUrl issued = issueOne("front.jpg", "image/jpeg");
            upload(issued, 200, 150);
            service.complete(MEMBER_ID, ACCIDENT_ID, completeRequest(issued.imageId()));

            jdbcTemplate.update("delete from accident where accident_id = ?", ACCIDENT_ID);

            assertThat(rows()).isZero();
            assertThat(variants(issued.imageId())).isEmpty();
        }
    }

    // ------------------------------------------------------------ 정본 제약

    @Nested
    @DisplayName("정본 제약")
    class Constraints {

        @Test
        @DisplayName("같은 이미지에 같은 variant 를 두 번 저장하면 uk_aia 가 막는다")
        void uniqueVariant() {
            long imageId = insertImage();
            insertAsset(imageId, "ORIGINAL", 100);

            assertThatThrownBy(() -> insertAsset(imageId, "ORIGINAL", 100))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("variant 에 OVERLAY 를 넣으면 ck_aia_var 가 막는다")
        void overlayRejected() {
            long imageId = insertImage();

            assertThatThrownBy(() -> insertAsset(imageId, "OVERLAY", 100))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("20MB 를 넘는 file_size 는 ck_aia_size 가 막는다")
        void sizeConstraint() {
            long imageId = insertImage();

            insertAsset(imageId, "ORIGINAL", 20_971_520);
            assertThatThrownBy(() -> insertAsset(imageId, "RESIZED", 20_971_521))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("quality_status 는 PASS·WARN 만 허용한다")
        void qualityStatusConstraint() {
            assertThatThrownBy(() -> jdbcTemplate.update("""
                    insert into accident_image(accident_id, original_filename, quality_status)
                    values (?, 'x.jpg', 'FAILED')
                    """, ACCIDENT_ID))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        private long insertImage() {
            jdbcTemplate.update("""
                    insert into accident_image(accident_id, original_filename) values (?, 'x.jpg')
                    """, ACCIDENT_ID);
            return jdbcTemplate.queryForObject("""
                    select max(image_id) from accident_image where accident_id = ?
                    """, Long.class, ACCIDENT_ID);
        }

        private void insertAsset(long imageId, String variant, int fileSize) {
            jdbcTemplate.update("""
                    insert into accident_image_asset(image_id, variant, s3_key, width, height, file_size)
                    values (?, ?, ?, 10, 10, ?)
                    """, imageId, variant, "accidents/1/images/" + imageId + "/x", fileSize);
        }
    }

    // ------------------------------------------------------- 조회 URL (S15P21A307-137)

    @Nested
    @DisplayName("조회용 presigned GET URL")
    class DownloadUrls {

        @Test
        @DisplayName("이미지가 없으면 빈 배열이다 — 404 가 아니다")
        void emptyList() {
            AccidentImageListResponse list = service.list(MEMBER_ID, ACCIDENT_ID);

            assertThat(list.total()).isZero();
            assertThat(list.images()).isEmpty();
            assertThat(list.remainingSlots()).isEqualTo(properties.maxCountPerAccident());
        }

        @Test
        @DisplayName("완료된 이미지에 RESIZED·THUMBNAIL 의 조회 URL 과 만료 시각을 준다")
        void issuesUrlsForDerivedVariants() {
            Instant before = Instant.now();
            IssuedUploadUrl issued = issueOne("front.jpg", "image/jpeg");
            upload(issued, 400, 300);
            service.complete(MEMBER_ID, ACCIDENT_ID, completeRequest(issued.imageId()));

            AccidentImageResponse image = service.list(MEMBER_ID, ACCIDENT_ID).images().get(0);

            assertThat(image.assets()).extracting("variant")
                    .containsExactly(ImageVariant.RESIZED, ImageVariant.THUMBNAIL);
            assertThat(image.assets()).allSatisfy(asset -> {
                assertThat(asset.url()).isNotBlank();
                assertThat(asset.expiresAt())
                        .as("만료 시각이 없으면 화면이 언제 다시 받아야 하는지 모른다")
                        .isNotNull()
                        .isAfter(before);
                assertThat(asset.width()).isNotNull();
                assertThat(asset.height()).isNotNull();
                assertThat(asset.fileSize()).isNotNull();
            });
        }

        @Test
        @DisplayName("ORIGINAL 은 URL 을 발급하지 않는다 — 서명 대상에서도 빠진다")
        void neverSignsOriginal() {
            IssuedUploadUrl issued = issueOne("front.jpg", "image/jpeg");
            upload(issued, 400, 300);
            service.complete(MEMBER_ID, ACCIDENT_ID, completeRequest(issued.imageId()));
            storage.downloadSigned.clear();

            AccidentImageResponse image = service.list(MEMBER_ID, ACCIDENT_ID).images().get(0);

            assertThat(image.assets()).extracting("variant").doesNotContain(ImageVariant.ORIGINAL);
            assertThat(storage.downloadSigned)
                    .as("원본 키에 서명을 시도하는 것 자체가 회귀다")
                    .noneMatch(key -> key.contains("/original."));
            // 저장은 그대로 3행이다 — 분석 파이프라인이 원본을 읽어야 한다.
            assertThat(variants(issued.imageId()))
                    .containsExactlyInAnyOrder("ORIGINAL", "RESIZED", "THUMBNAIL");
        }

        @Test
        @DisplayName("아직 완료되지 않은 이미지는 asset 이 없어 URL 도 없다")
        void pendingHasNoUrl() {
            issueOne("front.jpg", "image/jpeg");

            AccidentImageResponse image = service.list(MEMBER_ID, ACCIDENT_ID).images().get(0);

            assertThat(image.uploadState()).isEqualTo(ImageUploadState.PENDING);
            assertThat(image.assets()).isEmpty();
        }

        @Test
        @DisplayName("S3 오브젝트가 없어도 목록은 200 이다 — URL 은 열었을 때 404 가 난다")
        void missingObjectStillListsMetadata() {
            IssuedUploadUrl issued = issueOne("front.jpg", "image/jpeg");
            upload(issued, 400, 300);
            service.complete(MEMBER_ID, ACCIDENT_ID, completeRequest(issued.imageId()));
            // 누군가 버킷에서 오브젝트만 지운 상황. DB 행은 남아 있다.
            storage.objects.clear();

            AccidentImageResponse image = service.list(MEMBER_ID, ACCIDENT_ID).images().get(0);

            // presign 은 로컬 서명 계산이라 오브젝트 존재를 확인하지 않는다.
            // 확인하려면 asset 마다 HeadObject 왕복이 생긴다 — 목록이 그 비용을 지지 않는다.
            assertThat(image.assets()).hasSize(2);
            assertThat(image.assets()).allSatisfy(asset -> assertThat(asset.url()).isNotBlank());
        }

        @Test
        @DisplayName("서명이 실패하면 그 asset 만 url 이 비고 목록은 살아남는다")
        void signingFailureDegradesPerAsset() {
            IssuedUploadUrl issued = issueOne("front.jpg", "image/jpeg");
            upload(issued, 400, 300);
            service.complete(MEMBER_ID, ACCIDENT_ID, completeRequest(issued.imageId()));
            storage.failDownloadSigning = true;

            AccidentImageListResponse list = service.list(MEMBER_ID, ACCIDENT_ID);

            assertThat(list.total()).as("전체 요청을 500 으로 뒤집지 않는다").isEqualTo(1);
            AccidentImageResponse image = list.images().get(0);
            assertThat(image.assets()).hasSize(2);
            assertThat(image.assets()).allSatisfy(asset -> {
                assertThat(asset.url()).isNull();
                assertThat(asset.expiresAt()).isNull();
                assertThat(asset.width()).as("메타는 살아 있어야 한다").isNotNull();
            });
        }

        @Test
        @DisplayName("남의 사고 이미지는 조회할 수 없다 — 404 이고 서명도 일어나지 않는다")
        void otherMembersAccidentIsHidden() {
            IssuedUploadUrl issued = issueOne("front.jpg", "image/jpeg");
            upload(issued, 400, 300);
            service.complete(MEMBER_ID, ACCIDENT_ID, completeRequest(issued.imageId()));
            storage.downloadSigned.clear();

            assertThat(errorOf(() -> service.list(OTHER_MEMBER_ID, ACCIDENT_ID)))
                    .isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(storage.downloadSigned).isEmpty();
        }

        @Test
        @DisplayName("존재하지 않는 사고는 404 다")
        void missingAccident() {
            assertThat(errorOf(() -> service.list(MEMBER_ID, MISSING_ACCIDENT_ID)))
                    .isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("이미지 3장을 조회해도 쿼리가 이미지 수만큼 늘지 않는다 — asset 을 fetch join 한다")
        void noNPlusOneQuery() {
            for (int i = 0; i < 3; i++) {
                IssuedUploadUrl issued = issueOne("photo" + i + ".jpg", "image/jpeg");
                upload(issued, 400, 300);
                service.complete(MEMBER_ID, ACCIDENT_ID, completeRequest(issued.imageId()));
            }
            Statistics statistics = statistics();
            statistics.clear();

            AccidentImageListResponse list = service.list(MEMBER_ID, ACCIDENT_ID);

            assertThat(list.images()).hasSize(3);
            assertThat(list.images()).allSatisfy(image -> assertThat(image.assets()).hasSize(2));
            // 사고 소유자 확인 1 + 이미지·asset fetch join 1. 이미지마다 asset 을 따로 읽으면 늘어난다.
            assertThat(statistics.getPrepareStatementCount())
                    .as("N+1 이 생기면 이미지 수만큼 쿼리가 늘어난다")
                    .isLessThanOrEqualTo(2);
        }
    }

    // ------------------------------------------------------- angleCode (S15P21A307-137)

    @Nested
    @DisplayName("촬영 각도 저장")
    class AngleCodePersistence {

        @Test
        @DisplayName("발급 요청의 각도를 저장하고 발급·조회 응답에 그대로 준다")
        void persistsAndReturns() {
            IssuedUploadUrl issued = service.issueUploadUrls(MEMBER_ID, ACCIDENT_ID,
                    request(file("front.jpg", "image/jpeg", 1024, "FRONT"))).files().get(0);

            assertThat(issued.angleCode()).isEqualTo("FRONT");
            assertThat(angleCodeOf(issued.imageId()))
                    .as("DB 에 남아야 새로고침해도 각도가 살아 있다")
                    .isEqualTo("FRONT");
            assertThat(service.list(MEMBER_ID, ACCIDENT_ID).images().get(0).angleCode())
                    .isEqualTo("FRONT");
        }

        @Test
        @DisplayName("완료 통보 응답에도 저장된 각도가 실린다")
        void survivesCompletion() {
            IssuedUploadUrl issued = service.issueUploadUrls(MEMBER_ID, ACCIDENT_ID,
                    request(file("rear.jpg", "image/jpeg", 1024, "REAR_LEFT"))).files().get(0);
            upload(issued, 400, 300);

            ImageUploadCompleteResponse response =
                    service.complete(MEMBER_ID, ACCIDENT_ID, completeRequest(issued.imageId()));

            assertThat(response.results().get(0).angleCode()).isEqualTo("REAR_LEFT");
            assertThat(angleCodeOf(issued.imageId())).isEqualTo("REAR_LEFT");
        }

        @Test
        @DisplayName("각도를 보내지 않으면 null 이다 — 추측해서 채우지 않는다")
        void absentAngleStaysNull() {
            IssuedUploadUrl issued = issueOne("front.jpg", "image/jpeg");

            assertThat(issued.angleCode()).isNull();
            assertThat(angleCodeOf(issued.imageId())).isNull();
            assertThat(service.list(MEMBER_ID, ACCIDENT_ID).images().get(0).angleCode()).isNull();
        }

        @Test
        @DisplayName("컬럼이 생기기 전에 올라간 행처럼 angle_code 가 NULL 이어도 조회가 된다")
        void legacyNullRowIsReadable() {
            IssuedUploadUrl issued = service.issueUploadUrls(MEMBER_ID, ACCIDENT_ID,
                    request(file("front.jpg", "image/jpeg", 1024, "FRONT"))).files().get(0);
            jdbcTemplate.update(
                    "update accident_image set angle_code = null where image_id = ?", issued.imageId());

            AccidentImageResponse image = service.list(MEMBER_ID, ACCIDENT_ID).images().get(0);

            assertThat(image.angleCode()).isNull();
            assertThat(image.imageId()).isEqualTo(issued.imageId());
        }

        @Test
        @DisplayName("빈 문자열은 null 로 접는다 — 없음을 두 가지 상태로 두지 않는다")
        void blankFoldsToNull() {
            IssuedUploadUrl issued = service.issueUploadUrls(MEMBER_ID, ACCIDENT_ID,
                    request(file("front.jpg", "image/jpeg", 1024, "  "))).files().get(0);

            assertThat(angleCodeOf(issued.imageId())).isNull();
        }

        @Test
        @DisplayName("잘못된 각도는 행도 각도도 남기지 않는다 — 반쪽 저장이 없다")
        void invalidAngleLeavesNothing() {
            assertThatThrownBy(() -> service.issueUploadUrls(MEMBER_ID, ACCIDENT_ID,
                    request(file("ok.jpg", "image/jpeg", 1024, "FRONT"),
                            file("bad.jpg", "image/jpeg", 1024, "TOP_DOWN"))))
                    .isInstanceOf(AccidentImageValidationException.class);

            // 첫 파일이 유효해도 두 번째에서 걸리면 트랜잭션이 통째로 롤백된다.
            assertThat(rows()).isZero();
            assertThat(storage.issued).isEmpty();
        }

        private String angleCodeOf(Long imageId) {
            return jdbcTemplate.queryForObject(
                    "select angle_code from accident_image where image_id = ?", String.class, imageId);
        }
    }

    // ------------------------------------------------------------------ 도구

    private IssuedUploadUrl issueOne(String filename, String contentType) {
        return service.issueUploadUrls(MEMBER_ID, ACCIDENT_ID,
                request(file(filename, contentType, 1024, null))).files().get(0);
    }

    /** presigned 로 받은 키에 실제 JPEG 을 올려 브라우저 → S3 PUT 을 대신한다. */
    private byte[] upload(IssuedUploadUrl issued, int width, int height) {
        byte[] content = com.ssafy.a307.accident.image.TestImages.jpeg(width, height);
        storage.put(issued.s3Key(), content, "image/jpeg");
        return content;
    }

    private static ImageUploadCompleteRequest completeRequest(Long imageId) {
        return new ImageUploadCompleteRequest(List.of(new ImageUploadCompleteItem(imageId)));
    }

    private static ImageUploadUrlRequest request(ImageUploadUrlItem... files) {
        return new ImageUploadUrlRequest(List.of(files));
    }

    private static ImageUploadUrlItem[] files(int count) {
        ImageUploadUrlItem[] items = new ImageUploadUrlItem[count];
        for (int i = 0; i < count; i++) {
            items[i] = file("photo" + i + ".jpg", "image/jpeg", 1024, null);
        }
        return items;
    }

    private static ImageUploadUrlItem file(
            String filename, String contentType, long size, String angleCode) {
        return new ImageUploadUrlItem(filename, contentType, size, angleCode);
    }

    private int rows() {
        return jdbcTemplate.queryForObject("""
                select count(*) from accident_image where accident_id = ?
                """, Integer.class, ACCIDENT_ID);
    }

    private List<String> variants(Long imageId) {
        return jdbcTemplate.queryForList("""
                select variant from accident_image_asset where image_id = ? order by variant
                """, String.class, imageId);
    }

    /** Hibernate 통계. N+1 을 "느낌" 이 아니라 쿼리 수로 본다. */
    private Statistics statistics() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        return statistics;
    }

    private static ErrorCode errorOf(Runnable call) {
        BusinessException e = catchThrowableOfType(BusinessException.class, call::run);
        assertThat(e).as("BusinessException 이 던져져야 한다").isNotNull();
        return e.getErrorCode();
    }

    private void insertAccident(long accidentId, long vehicleId) {
        jdbcTemplate.update("""
                insert into accident(
                    accident_id, vehicle_id, vehicle_input_type, snapshot_model_id,
                    snapshot_manufacturer, snapshot_model_name, snapshot_vehicle_type,
                    snapshot_car_class, snapshot_model_year)
                values (?, ?, 'REGISTERED', ?, '이미지제조사', '이미지차량', 'SEDAN', 'Compact', 2020)
                """, accidentId, vehicleId, MODEL_ID);
    }

    private void cleanUp() {
        jdbcTemplate.update("""
                delete from accident_image_asset where image_id in (
                    select image_id from accident_image where accident_id in (?, ?))
                """, ACCIDENT_ID, OTHER_ACCIDENT_ID);
        jdbcTemplate.update(
                "delete from accident_image where accident_id in (?, ?)", ACCIDENT_ID, OTHER_ACCIDENT_ID);
        jdbcTemplate.update(
                "delete from accident where accident_id in (?, ?)", ACCIDENT_ID, OTHER_ACCIDENT_ID);
        jdbcTemplate.update(
                "delete from vehicle where vehicle_id in (?, ?)", VEHICLE_ID, OTHER_VEHICLE_ID);
        jdbcTemplate.update("delete from vehicle_model where model_id = ?", MODEL_ID);
        jdbcTemplate.update(
                "delete from member where member_id in (?, ?)", MEMBER_ID, OTHER_MEMBER_ID);
    }
}
