package com.ssafy.a307.estimatevalidation.service;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.estimatevalidation.domain.ValidationStatus;
import com.ssafy.a307.estimatevalidation.dto.FileValidationMetadata;
import com.ssafy.a307.estimatevalidation.dto.ManualValidationItemRequest;
import com.ssafy.a307.estimatevalidation.dto.ManualValidationRequest;
import com.ssafy.a307.estimatevalidation.domain.EstimateFileType;
import com.ssafy.a307.estimatevalidation.file.DocumentStoragePort;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@SpringBootTest
@Transactional
class EstimateFileValidationServiceTest {

    private static final long ME = 701L;
    private static final long ACCIDENT = 703L;

    @Autowired EstimateFileValidationService service;
    @Autowired EstimateValidationService validationService;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @MockitoBean DocumentStoragePort storage;

    @BeforeEach
    void setUp() {
        jdbc.update("insert into member(member_id,provider,provider_user_id,nickname,role,status) values(?,'KAKAO','file-member','tester','USER','ACTIVE')", ME);
        jdbc.update("insert into vehicle_model(model_id,manufacturer,model_name,vehicle_type,car_class,is_active) values(702,'현대','쏘나타','SEDAN','Mid-size',true)");
        jdbc.update("insert into vehicle(vehicle_id,member_id,model_id,model_year) values(702,?,702,2025)", ME);
        jdbc.update("""
                insert into accident(
                    accident_id, vehicle_id, vehicle_input_type,
                    snapshot_model_id, snapshot_manufacturer, snapshot_model_name,
                    snapshot_vehicle_type, snapshot_car_class, snapshot_model_year)
                values(?, 702, 'REGISTERED', 702, '현대', '쏘나타', 'SEDAN', 'Mid-size', 2025)
                """, ACCIDENT);
    }

    @Test
    void validPdfIsStoredWithGeneratedKeyAndRemainsQueuedForOcrProvider() {
        given(storage.store(any())).willReturn(
                new DocumentStoragePort.StoredDocument("private/estimate/uuid.pdf", 8, "application/pdf"));
        var file = new MockMultipartFile(
                "file", "estimate.pdf", MediaType.APPLICATION_PDF_VALUE,
                new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '7'});

        var response = service.registerFile(ME, new FileValidationMetadata(ACCIDENT, null), file);

        assertThat(response.status()).isEqualTo(ValidationStatus.QUEUED);
        assertThat(jdbc.queryForObject(
                "select s3_key_file from estimate_validation where validation_id=?",
                String.class, response.validationId())).isEqualTo("private/estimate/uuid.pdf");
        then(storage).should().store(any(DocumentStoragePort.StoreDocument.class));
    }

    @Test
    void objectDeletionFailureLeavesDatabaseRowsForRetry() {
        given(storage.store(any())).willReturn(
                new DocumentStoragePort.StoredDocument("private/estimate/fail.pdf", 8, "application/pdf"));
        var file = new MockMultipartFile(
                "file", "estimate.pdf", MediaType.APPLICATION_PDF_VALUE,
                new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '7'});
        long validationId = service.registerFile(
                ME, new FileValidationMetadata(ACCIDENT, null), file).validationId();
        org.mockito.BDDMockito.willThrow(new IllegalStateException("storage unavailable"))
                .given(storage).deleteAll(List.of("private/estimate/fail.pdf"));

        assertThatThrownBy(() -> service.delete(ME, validationId))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject(
                "select count(*) from estimate_validation where validation_id=?",
                Integer.class, validationId)).isEqualTo(1);
    }

    @Test
    void pdfRequiresCompletedReportAndUsesExternalizedExpiry() {
        given(storage.store(any())).willReturn(
                new DocumentStoragePort.StoredDocument("private/estimate/source.pdf", 8, "application/pdf"));
        var file = new MockMultipartFile(
                "file", "estimate.pdf", MediaType.APPLICATION_PDF_VALUE,
                new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '7'});
        long validationId = service.registerFile(
                ME, new FileValidationMetadata(ACCIDENT, null), file).validationId();

        assertThatThrownBy(() -> service.pdfDownload(ME, validationId))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        then(storage).should(never()).createPresignedDownloadUrl(any(), any());

        jdbc.update("update estimate_validation_report set status='COMPLETED', s3_key_pdf='private/report/result.pdf', completed_at=now() where validation_id=?", validationId);
        entityManager.clear();
        URI uri = URI.create("https://example.invalid/result.pdf?sig=test");
        given(storage.createPresignedDownloadUrl(
                "private/report/result.pdf", Duration.ofMinutes(10))).willReturn(uri);

        assertThat(service.pdfDownload(ME, validationId)).isEqualTo(uri);
        then(storage).should().createPresignedDownloadUrl(
                eq("private/report/result.pdf"), eq(Duration.ofMinutes(10)));
    }

    @Test
    void manualDeletionCascadesItemsQuestionsAndReportWithoutStorageCall() {
        long validationId = validationService.registerManual(ME,
                new ManualValidationRequest(ACCIDENT, null, EstimateFileType.MANUAL, null,
                        List.of(new ManualValidationItemRequest(
                                1, "알 수 없는 항목", "수리", 1, 10_000, 0))))
                .validationId();
        entityManager.flush();
        assertThat(jdbc.queryForObject(
                "select count(*) from estimate_validation_item where validation_id=?",
                Integer.class, validationId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from estimate_validation_question where validation_id=?",
                Integer.class, validationId)).isEqualTo(1);

        service.delete(ME, validationId);

        assertThat(jdbc.queryForObject(
                "select count(*) from estimate_validation where validation_id=?",
                Integer.class, validationId)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from estimate_validation_item where validation_id=?",
                Integer.class, validationId)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from estimate_validation_question where validation_id=?",
                Integer.class, validationId)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from estimate_validation_report where validation_id=?",
                Integer.class, validationId)).isZero();
        then(storage).should(never()).deleteAll(any());
    }
}
