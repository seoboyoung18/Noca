package com.ssafy.a307.estimatevalidation.service;

import com.ssafy.a307.accident.entity.Accident;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.estimatevalidation.domain.EstimateFileType;
import com.ssafy.a307.estimatevalidation.dto.FileValidationMetadata;
import com.ssafy.a307.estimatevalidation.entity.EstimateValidation;
import com.ssafy.a307.estimatevalidation.file.DocumentStoragePort;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/**
 * 견적서 파일 업로드 경로. <b>저장소가 붙은 뒤에 지켜져야 하는 것들</b>을 고정한다.
 *
 * <p>{@code ck_ev_file} 은 "직접 입력이면 {@code s3_key_file} 이 NULL, 파일 입력이면 NOT NULL"
 * 을 강제한다. 그래서 <b>저장이 먼저 성공하고 그 다음 INSERT</b> 여야 하고, 저장은 됐는데
 * INSERT 가 실패하면 <b>올린 객체가 고아로 남는다</b>. 두 가지를 여기서 확인한다.
 *
 * <p>실제 AWS 를 부르지 않는다 — 저장소 포트를 목으로 대체한다.
 */
@SpringBootTest
@Transactional
@DisplayName("견적서 파일 업로드 경로")
class EstimateFileUploadPathTest {

    private static final long ME = 811L;
    private static final long OTHER = 812L;
    private static final long ACCIDENT = 813L;

    private static final byte[] PDF_BYTES = {'%', 'P', 'D', 'F', '-', '1', '.', '7'};

    @Autowired EstimateFileValidationService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @MockitoBean DocumentStoragePort storage;

    @BeforeEach
    void setUp() {
        jdbc.update("insert into member(member_id,provider,provider_user_id,nickname,role,status)"
                + " values(?,'KAKAO','upload-owner','tester','USER','ACTIVE')", ME);
        jdbc.update("insert into member(member_id,provider,provider_user_id,nickname,role,status)"
                + " values(?,'KAKAO','upload-other','tester','USER','ACTIVE')", OTHER);
        jdbc.update("insert into vehicle_model(model_id,manufacturer,model_name,vehicle_type,car_class,is_active)"
                + " values(810,'기아','K5','SEDAN','Mid-size',true)");
        jdbc.update("insert into vehicle(vehicle_id,member_id,model_id,model_year) values(810,?,810,2025)", ME);
        jdbc.update("""
                insert into accident(
                    accident_id, vehicle_id, vehicle_input_type,
                    snapshot_model_id, snapshot_manufacturer, snapshot_model_name,
                    snapshot_vehicle_type, snapshot_car_class, snapshot_model_year)
                values(?, 810, 'REGISTERED', 810, '기아', 'K5', 'SEDAN', 'Mid-size', 2025)
                """, ACCIDENT);
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile("file", "차주이름_견적서.pdf", MediaType.APPLICATION_PDF_VALUE, PDF_BYTES);
    }

    private long upload(String key) {
        given(storage.store(any())).willReturn(
                new DocumentStoragePort.StoredDocument(key, PDF_BYTES.length, "application/pdf"));
        return service.registerFile(ME, new FileValidationMetadata(ACCIDENT, null), pdf()).validationId();
    }

    // ------------------------------------------------------------------ 저장 → INSERT 순서

    @Test
    @DisplayName("저장이 먼저 성공하고 그 키로 INSERT 한다")
    void storesBeforeInsert() {
        long validationId = upload("estimates/2026/09/aaaa.pdf");

        then(storage).should().store(any(DocumentStoragePort.StoreDocument.class));
        assertThat(jdbc.queryForObject(
                "select s3_key_file from estimate_validation where validation_id=?",
                String.class, validationId)).isEqualTo("estimates/2026/09/aaaa.pdf");
    }

    /**
     * 저장이 실패하면 INSERT 를 시도조차 하지 않는다. {@code ck_ev_file} 이 파일 입력에 키를
     * 요구하므로 순서가 뒤집히면 CHECK 위반으로 깨진다 — 그래서 저장이 먼저다.
     */
    @Test
    @DisplayName("저장이 실패하면 503이고 행을 만들지 않는다")
    void storageFailureLeavesNoRow() {
        given(storage.store(any())).willThrow(
                new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "문서를 저장하지 못했습니다."));

        assertThatThrownBy(() -> service.registerFile(ME, new FileValidationMetadata(ACCIDENT, null), pdf()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThat(jdbc.queryForObject(
                "select count(*) from estimate_validation where accident_id=?",
                Integer.class, ACCIDENT)).isZero();
    }

    /**
     * <b>고아 객체를 만들지 않는다.</b> 저장은 됐는데 INSERT 가 깨지면 올라간 오브젝트를
     * 가리키는 행이 없어 아무도 지울 수 없다. service 버킷에는 수명 정책이 없으므로
     * (staging 과 달리) 영원히 남는다 — 그래서 보상 삭제가 필요하다.
     *
     * <p>어댑터가 규약을 어겨 {@code s3_key_file VARCHAR(500)} 을 넘는 키를 준 상황으로 재현한다.
     */
    @Test
    @DisplayName("저장 성공 후 INSERT 가 실패하면 올린 객체를 지운다 — 고아로 두지 않는다")
    void compensatesStoredObjectWhenInsertFails() {
        String tooLongKey = "estimates/2026/09/" + "a".repeat(600) + ".pdf";

        assertThatThrownBy(() -> upload(tooLongKey)).isInstanceOf(RuntimeException.class);

        then(storage).should().deleteAll(List.of(tooLongKey));
        assertThat(jdbc.queryForObject(
                "select count(*) from estimate_validation where accident_id=?",
                Integer.class, ACCIDENT)).isZero();
    }

    // ------------------------------------------------------------------ ck_ev_file

    @Nested
    @DisplayName("ck_ev_file — 입력 형태와 키가 어긋나면 저장되지 않는다")
    class FileKeyConstraint {

        private Accident accident() {
            return entityManager.find(Accident.class, ACCIDENT);
        }

        @Test
        @DisplayName("직접 입력인데 키가 있으면 거절한다")
        void manualWithKeyIsRejected() {
            EstimateValidation invalid = EstimateValidation.queuedFile(
                    ME, accident(), null, "estimates/2026/09/should-not-exist.pdf", EstimateFileType.MANUAL);

            // 식별자 생성이 INSERT 시점이라 persist 에서 이미 터진다. 둘 다 감싼다.
            assertThatThrownBy(() -> {
                entityManager.persist(invalid);
                entityManager.flush();
            }).hasMessageContaining("CK_EV_FILE");
        }

        @Test
        @DisplayName("파일 입력인데 키가 없으면 거절한다")
        void fileWithoutKeyIsRejected() {
            EstimateValidation invalid = EstimateValidation.queuedFile(
                    ME, accident(), null, null, EstimateFileType.PDF);

            assertThatThrownBy(() -> {
                entityManager.persist(invalid);
                entityManager.flush();
            }).hasMessageContaining("CK_EV_FILE");
        }
    }

    // ------------------------------------------------------------------ 다중 등록 · 삭제 · 소유권

    @Test
    @DisplayName("한 사고에 여러 견적서를 올려도 키가 겹치지 않는다")
    void keysDoNotCollideAcrossValidations() {
        var keys = new java.util.ArrayList<String>();
        given(storage.store(any())).willAnswer(invocation -> {
            var request = (DocumentStoragePort.StoreDocument) invocation.getArgument(0);
            String key = com.ssafy.a307.estimatevalidation.file.EstimateDocumentKeys
                    .documentKey(request.extension(), java.time.Instant.now());
            keys.add(key);
            return new DocumentStoragePort.StoredDocument(key, request.content().length, request.contentType());
        });

        for (int i = 0; i < 5; i++) {
            service.registerFile(ME, new FileValidationMetadata(ACCIDENT, null), pdf());
        }

        assertThat(keys).hasSize(5).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("삭제하면 저장소의 원본 키도 함께 지운다")
    void deleteRemovesStoredObject() {
        long validationId = upload("estimates/2026/09/bbbb.pdf");

        service.delete(ME, validationId);

        then(storage).should().deleteAll(List.of("estimates/2026/09/bbbb.pdf"));
        assertThat(jdbc.queryForObject(
                "select count(*) from estimate_validation where validation_id=?",
                Integer.class, validationId)).isZero();
    }

    @Test
    @DisplayName("남의 검증은 404다 — 저장소를 건드리지 않는다")
    void otherMembersValidationIsNotFound() {
        long validationId = upload("estimates/2026/09/cccc.pdf");

        assertThatThrownBy(() -> service.delete(OTHER, validationId))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.pdfDownload(OTHER, validationId))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        then(storage).should(org.mockito.Mockito.never())
                .deleteAll(List.of("estimates/2026/09/cccc.pdf"));
    }
}
