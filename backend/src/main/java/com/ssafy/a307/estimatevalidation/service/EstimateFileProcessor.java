package com.ssafy.a307.estimatevalidation.service;

import com.ssafy.a307.accident.entity.Accident;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.estimatevalidation.domain.EstimateLine;
import com.ssafy.a307.estimatevalidation.dto.ManualValidationItemRequest;
import com.ssafy.a307.estimatevalidation.entity.EstimateValidation;
import com.ssafy.a307.estimatevalidation.file.EstimateOcrPort;
import com.ssafy.a307.estimatevalidation.file.UnsupportedDocumentFormatException;
import com.ssafy.a307.estimatevalidation.repository.EstimateReadRepository;
import com.ssafy.a307.estimatevalidation.repository.EstimateValidationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 파일 검증 <b>한 건</b>의 처리 — 항목 추출 → 직접 입력과 같은 파이프라인 → 완료.
 *
 * <p><b>워커와 별개 빈인 이유는 트랜잭션 경계다.</b> 한 건의 실패가 같은 주기의 다른 건을
 * 오염시키면 안 되므로 건마다 트랜잭션이 따로 끊어져야 하는데, 자기 자신을 호출하면 프록시를
 * 거치지 않아 {@code REQUIRES_NEW} 가 먹지 않는다. {@code AccidentImageIngestService} 가
 * 같은 이유로 별개 빈이다.
 *
 * <p><b>실패 기록도 별개 트랜잭션이다.</b> {@link #process} 가 던지고 나면 그 트랜잭션은
 * 롤백된다. 같은 트랜잭션 안에서 {@code fail()} 을 쓰면 실패 기록까지 함께 사라져
 * 건이 {@code PROCESSING} 인 채 남는다. 그래서 워커가 {@link #markFailed} 를 따로 부른다.
 *
 * <p><b>원본 바이트는 이 클래스가 읽지 않는다.</b> {@code EstimateOcrPort.OcrDocument} 가
 * 저장소 키를 들고 다니므로 파일을 여는 것은 OCR 어댑터의 몫이다. 여기서도 읽으면 같은 파일을
 * 두 번 읽게 된다.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.estimate-worker", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class EstimateFileProcessor {

    /** {@code ManualValidationRequest} 의 항목 수 상한과 같다. */
    private static final int MAX_ITEMS = 200;
    /** {@code lineNo}·{@code quantity} 상한. DB 가 SMALLINT 다. */
    private static final int MAX_SMALLINT = 32767;
    /** {@code ManualValidationItemRequest.rawItemName} 상한. */
    private static final int MAX_ITEM_NAME_LENGTH = 200;
    /** 문서상 총액이 항목 합계와 이 비율을 넘게 어긋나면 로그로 남긴다. 실패시키지는 않는다. */
    private static final double DOCUMENT_TOTAL_TOLERANCE = 0.10;

    private final EstimateValidationRepository validationRepository;
    private final EstimateValidationService validationService;

    /**
     * 워커를 켰다면 판독 어댑터가 반드시 있어야 한다. {@code Optional} 로 받지 않는 것은 의도다 —
     * 어댑터 없이 도는 워커는 큐를 비우지 못하면서 매 주기 실패만 쌓는다. 기동 때 실패하는 편이 낫다.
     */
    private final EstimateOcrPort ocrPort;

    /**
     * 큐에서 한 건을 선점한다.
     *
     * <p><b>워커가 아니라 여기 있는 이유</b> — 워커 안에 두고 {@code this.claim(...)} 으로 부르면
     * 프록시를 거치지 않아 {@code @Transactional} 이 먹지 않고, {@code @Modifying} 쿼리가
     * 트랜잭션 없이 실행되어 터진다. 이 클래스 자체가 "트랜잭션 경계를 위해 나눈 빈" 이므로
     * 선점도 여기에 둔다.
     *
     * <p>선점은 처리와도 분리돼야 한다. 처리 트랜잭션이 롤백돼도 선점은 남아야 같은 건을
     * 무한히 다시 집지 않는다.
     *
     * @return 내가 선점했으면 true. 0행이면 다른 워커가 이미 가져갔다는 뜻이라 건너뛴다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(Long validationId) {
        return validationRepository.claimQueued(validationId) == 1;
    }

    /**
     * @throws EstimateProcessingException 이 건만 실패. 워커가 받아 {@link #markFailed} 로 기록한다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(Long validationId) {
        EstimateValidation validation = validationRepository.findById(validationId)
                .orElseThrow(() -> new EstimateProcessingException(
                        EstimateValidationFailure.INTERNAL, "선점한 검증이 사라졌다: " + validationId));
        requireStorageKey(validation);

        EstimateOcrPort.OcrExtraction extraction = extract(validation);
        List<ManualValidationItemRequest> items = toRequests(extraction, validationId);
        List<EstimateLine> lines = toLines(items, validationId);
        int claimedTotal = totalOf(lines, extraction, validationId);

        Accident accident = validation.getAccident();
        EstimateReadRepository.EstimateContextView estimate = validationService.validateEstimate(
                validation.getEstimateId(), accident.getAccidentId());

        validationService.runPipeline(validation, accident, estimate, lines, claimedTotal, Instant.now());
        log.info("견적서 검증 완료: validationId={}, 항목={}건, 총액={}",
                validationId, lines.size(), claimedTotal);
    }

    /**
     * 실패를 기록한다. <b>{@link #process} 와 다른 트랜잭션이어야 한다.</b>
     *
     * <p>이미 끝난 건은 건드리지 않는다 — 워커 둘이 겹쳐 돌 때 한쪽이 완료한 건을
     * 다른 쪽이 실패로 덮는 일을 막는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long validationId, EstimateValidationFailure failure) {
        validationRepository.findById(validationId).ifPresent(validation -> {
            if (validation.getCompletedAt() != null) return;
            validation.fail(failure.userMessage(), Instant.now());
        });
    }

    private void requireStorageKey(EstimateValidation validation) {
        String key = validation.getS3KeyFile();
        if (key == null || key.isBlank()) {
            throw new EstimateProcessingException(
                    EstimateValidationFailure.STORAGE_READ,
                    "파일 입력인데 저장소 키가 없다: " + validation.getValidationId());
        }
    }

    /**
     * 판독을 부른다. 실패 사유를 둘로 가른다.
     *
     * <p>저장소에서 원본을 못 찾은 것({@code NOT_FOUND})은 <b>사용자가 다시 올리면 풀리는</b>
     * 문제이고, 그 밖의 실패는 판독 자체가 안 된 것이라 안내 문구가 달라야 한다.
     * 저장소는 {@code DocumentStoragePort} 규약대로 없는 키에 {@code NOT_FOUND} 를 준다.
     */
    private EstimateOcrPort.OcrExtraction extract(EstimateValidation validation) {
        try {
            return ocrPort.extract(new EstimateOcrPort.OcrDocument(
                    validation.getS3KeyFile(), validation.getFileType()));
        } catch (UnsupportedDocumentFormatException e) {
            throw new EstimateProcessingException(
                    EstimateValidationFailure.UNSUPPORTED_FORMAT,
                    "공급자가 지원하지 않는 형식이다: " + validation.getValidationId(), e);
        } catch (BusinessException e) {
            EstimateValidationFailure failure = e.getErrorCode() == ErrorCode.NOT_FOUND
                    ? EstimateValidationFailure.STORAGE_READ
                    : EstimateValidationFailure.EXTRACTION;
            throw new EstimateProcessingException(
                    failure, "판독에 실패했다: " + validation.getValidationId(), e);
        } catch (RuntimeException e) {
            throw new EstimateProcessingException(
                    EstimateValidationFailure.EXTRACTION,
                    "판독에 실패했다: " + validation.getValidationId(), e);
        }
    }

    /**
     * 추출 결과를 직접 입력 요청과 <b>같은 모양</b>으로 바꾼다.
     *
     * <p>여기서 형태를 맞추는 이유는 뒤이어 부르는 {@code toEstimateLines} 가 직접 입력이 쓰는
     * 바로 그 매핑·범위 검사이기 때문이다. <b>모델이 냈다고 검증을 느슨하게 하지 않는다.</b>
     *
     * <p>어댑터가 이미 항목별로 걸렀지만 여기서 한 번 더 본다.
     * {@code ManualValidationItemRequest} 의 상한(금액이 {@code Integer} 범위, 수량·행번호가
     * SMALLINT)은 어댑터가 아니라 이 DTO 와 스키마의 계약이라 경계에서 확인하는 편이 맞다.
     */
    private List<ManualValidationItemRequest> toRequests(
            EstimateOcrPort.OcrExtraction extraction, Long validationId) {
        List<ManualValidationItemRequest> result = new ArrayList<>(extraction.items().size());
        int discarded = 0;
        for (EstimateOcrPort.OcrLineItem item : extraction.items()) {
            if (outOfRange(item)) {
                discarded++;
                continue;
            }
            result.add(new ManualValidationItemRequest(
                    item.lineNo(), item.rawItemName(), item.workType(),
                    item.quantity(), (int) item.partCost(), (int) item.laborCost()));
        }
        if (discarded > 0) {
            log.warn("범위를 벗어난 항목 {}건을 버렸다. validationId={}", discarded, validationId);
        }
        if (result.isEmpty()) {
            // 빈 견적서를 "적정" 으로 판정하면 안 된다.
            throw new EstimateProcessingException(
                    EstimateValidationFailure.NO_ITEMS, "쓸 수 있는 항목이 없다: " + validationId);
        }
        if (result.size() > MAX_ITEMS) {
            throw new EstimateProcessingException(
                    EstimateValidationFailure.ITEM_MAPPING,
                    "항목 수가 상한을 넘었다(" + result.size() + "): " + validationId);
        }
        return result;
    }

    private static boolean outOfRange(EstimateOcrPort.OcrLineItem item) {
        return item.lineNo() > MAX_SMALLINT
                || item.quantity() > MAX_SMALLINT
                || item.partCost() > Integer.MAX_VALUE
                || item.laborCost() > Integer.MAX_VALUE
                || (item.partCost() == 0 && item.laborCost() == 0)
                || item.rawItemName().strip().length() > MAX_ITEM_NAME_LENGTH;
    }

    private List<EstimateLine> toLines(List<ManualValidationItemRequest> items, Long validationId) {
        try {
            return validationService.toEstimateLines(items);
        } catch (RuntimeException e) {
            throw new EstimateProcessingException(
                    EstimateValidationFailure.ITEM_MAPPING,
                    "항목을 해석하지 못했다: " + validationId, e);
        }
    }

    /**
     * <b>항목 소계 합계를 총액으로 삼는다.</b> 모델이 읽은 문서상 총액으로 덮어쓰지 않는다.
     *
     * <p>{@code claimedTotal} 은 화면에서 항목 표와 나란히 놓이고 AI 중앙값 비교의 기준이 된다.
     * 문서상 총액에는 부가세·할인이 별도 줄로 섞여 들어와 항목 합계와 자주 어긋나는데, 그 값을
     * 넣으면 표의 합과 총액이 맞지 않는 화면이 된다. 직접 입력 경로가 둘의 불일치를 400 으로
     * 막는 것과 같은 이유다.
     *
     * <p>차이가 크면 로그로만 남긴다. 사용자에게 알릴 응답 계약이 없고, 계약을 바꾸는 것은
     * 이 작업의 범위가 아니다.
     */
    private int totalOf(List<EstimateLine> lines, EstimateOcrPort.OcrExtraction extraction, Long validationId) {
        long total = 0;
        try {
            for (EstimateLine line : lines) total = Math.addExact(total, line.subtotal());
            int calculated = Math.toIntExact(total);
            warnIfDocumentTotalDiffers(calculated, extraction.claimedTotal(), validationId);
            return calculated;
        } catch (ArithmeticException e) {
            throw new EstimateProcessingException(
                    EstimateValidationFailure.ITEM_MAPPING,
                    "합계가 허용 범위를 벗어났다: " + validationId, e);
        }
    }

    private void warnIfDocumentTotalDiffers(int calculated, Long documentTotal, Long validationId) {
        if (documentTotal == null || documentTotal <= 0) return;
        double gap = Math.abs(documentTotal - (double) calculated) / documentTotal;
        if (gap > DOCUMENT_TOTAL_TOLERANCE) {
            log.warn("문서상 총액과 항목 합계가 약 {}% 어긋난다. 항목 합계를 쓴다. validationId={}",
                    Math.round(gap * 100), validationId);
        }
    }
}
