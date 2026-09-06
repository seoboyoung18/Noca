package com.ssafy.a307.estimatevalidation.service;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.estimatevalidation.config.EstimateValidationProperties;
import com.ssafy.a307.estimatevalidation.dto.FileValidationMetadata;
import com.ssafy.a307.estimatevalidation.dto.ValidationAcceptedResponse;
import com.ssafy.a307.estimatevalidation.entity.EstimateValidation;
import com.ssafy.a307.estimatevalidation.entity.EstimateValidationReport;
import com.ssafy.a307.estimatevalidation.file.DocumentStoragePort;
import com.ssafy.a307.estimatevalidation.file.EstimateFileValidator;
import com.ssafy.a307.estimatevalidation.file.ValidatedEstimateFile;
import com.ssafy.a307.estimatevalidation.repository.EstimateValidationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class EstimateFileValidationService {

    private final EstimateValidationService validationService;
    private final EstimateValidationRepository validationRepository;
    private final EstimateFileValidator fileValidator;
    private final Optional<DocumentStoragePort> storagePort;
    private final EstimateValidationProperties properties;

    @Transactional
    public ValidationAcceptedResponse registerFile(
            Long memberId, FileValidationMetadata metadata, MultipartFile multipartFile) {
        ValidatedEstimateFile file = fileValidator.validate(multipartFile);
        var accident = validationService.ownedAccident(memberId, metadata.accidentId());
        validationService.validateEstimate(metadata.estimateId(), metadata.accidentId());
        DocumentStoragePort storage = storage();
        String key = storage.store(new DocumentStoragePort.StoreDocument(
                file.content(), file.contentType(), file.extension())).storageKey();
        if (key == null || key.isBlank()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "문서 저장소가 유효한 키를 반환하지 않았습니다.");
        }
        try {
            EstimateValidation validation = EstimateValidation.queuedFile(
                    memberId, accident, metadata.estimateId(), key, file.fileType());
            validation.initializeReport();
            validationRepository.saveAndFlush(validation);
            return new ValidationAcceptedResponse(
                    validation.getValidationId(), validation.getStatus(), validation.getFileType(),
                    "/api/estimate-validations/" + validation.getValidationId());
        } catch (RuntimeException persistenceFailure) {
            try {
                storage.deleteAll(List.of(key));
            } catch (RuntimeException cleanupFailure) {
                persistenceFailure.addSuppressed(cleanupFailure);
            }
            throw persistenceFailure;
        }
    }

    @Transactional
    public void delete(Long memberId, Long validationId) {
        EstimateValidation validation = validationRepository.findByValidationIdAndMemberId(validationId, memberId)
                .orElseThrow(this::notFound);
        List<String> keys = new ArrayList<>(2);
        if (validation.getS3KeyFile() != null) keys.add(validation.getS3KeyFile());
        EstimateValidationReport report = validation.getReport();
        if (report != null && report.getS3KeyPdf() != null) keys.add(report.getS3KeyPdf());
        if (!keys.isEmpty()) storage().deleteAll(List.copyOf(keys));
        validationRepository.delete(validation);
        validationRepository.flush();
    }

    @Transactional(readOnly = true)
    public URI pdfDownload(Long memberId, Long validationId) {
        EstimateValidation validation = validationRepository.findByValidationIdAndMemberId(validationId, memberId)
                .orElseThrow(this::notFound);
        EstimateValidationReport report = validation.getReport();
        if (report == null || report.getStatus() != com.ssafy.a307.estimatevalidation.domain.ValidationStatus.COMPLETED
                || report.getS3KeyPdf() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "완료된 검증 PDF가 없습니다.");
        }
        return storage().createPresignedDownloadUrl(
                report.getS3KeyPdf(), Duration.ofMinutes(properties.presignedUrlMinutes()));
    }

    private DocumentStoragePort storage() {
        return storagePort.orElseThrow(() -> new BusinessException(
                ErrorCode.SERVICE_UNAVAILABLE,
                "문서 저장소 공급자가 구성되지 않았습니다. 직접 입력을 이용해 주세요."));
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, "견적서 검증을 찾을 수 없습니다.");
    }
}
