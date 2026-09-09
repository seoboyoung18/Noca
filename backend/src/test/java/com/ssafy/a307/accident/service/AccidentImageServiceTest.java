package com.ssafy.a307.accident.service;

import com.ssafy.a307.accident.config.AccidentImageProperties;
import com.ssafy.a307.accident.dto.AccidentImageListResponse;
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

        void reset() {
            objects.clear();
            contentTypes.clear();
            issued.clear();
            deleted.clear();
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

            BusinessException e = catchThrowableOfType(BusinessException.class,
                    () -> service.issueUploadUrls(MEMBER_ID, ACCIDENT_ID, request(files(2))));

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
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
            BusinessException e = catchThrowableOfType(BusinessException.class,
                    () -> service.issueUploadUrls(MEMBER_ID, ACCIDENT_ID,
                            request(file("iphone.heic", "image/heic", 1024, null))));

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
            assertThat(e).hasMessageContaining("JPG");
            assertThat(rows()).isZero();
        }

        @Test
        @DisplayName("촬영 가이드에 없는 각도 코드는 400 이다")
        void unknownAngleCode() {
            BusinessException e = catchThrowableOfType(BusinessException.class,
                    () -> service.issueUploadUrls(MEMBER_ID, ACCIDENT_ID,
                            request(file("front.jpg", "image/jpeg", 1024, "TOP_DOWN"))));

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
            assertThat(e).hasMessageContaining("TOP_DOWN");
        }

        @Test
        @DisplayName("한 파일이 걸리면 나머지도 발급되지 않는다 — 검증을 먼저 전부 한다")
        void allOrNothing() {
            assertThatThrownBy(() -> service.issueUploadUrls(MEMBER_ID, ACCIDENT_ID,
                    request(file("ok.jpg", "image/jpeg", 1024, null),
                            file("bad.gif", "image/gif", 1024, null))))
                    .isInstanceOf(BusinessException.class);

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
            assertThat(result.assets()).extracting("s3Key")
                    .doesNotContain(issued.s3Key());

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
