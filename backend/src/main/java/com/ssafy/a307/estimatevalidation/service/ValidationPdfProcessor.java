package com.ssafy.a307.estimatevalidation.service;

import com.ssafy.a307.estimatevalidation.domain.ValidationStatus;
import com.ssafy.a307.estimatevalidation.dto.ValidationResultResponse;
import com.ssafy.a307.estimatevalidation.entity.EstimateValidation;
import com.ssafy.a307.estimatevalidation.entity.EstimateValidationReport;
import com.ssafy.a307.estimatevalidation.file.DocumentStoragePort;
import com.ssafy.a307.estimatevalidation.file.PdfGenerationPort;
import com.ssafy.a307.estimatevalidation.file.pdf.ReportNarrative;
import com.ssafy.a307.estimatevalidation.file.pdf.ReportNarrativeGenerator;
import com.ssafy.a307.estimatevalidation.file.pdf.ValidationPdfAssembler;
import com.ssafy.a307.estimatevalidation.repository.EstimateValidationReportRepository;
import com.ssafy.a307.estimatevalidation.repository.EstimateValidationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * PDF <b>한 건</b>의 생성 — 결과 조회 → LLM 문장 → HTML → PDF → 저장소 → 상태 전이.
 *
 * <p><b>워커와 별개 빈인 이유는 트랜잭션 경계다.</b> 한 건의 실패가 같은 주기의 다른 건을
 * 오염시키면 안 되므로 건마다 트랜잭션이 따로 끊어져야 하는데, 자기 자신을 호출하면
 * 프록시를 거치지 않아 {@code REQUIRES_NEW} 가 먹지 않는다.
 * {@code AccidentImageIngestService} 가 같은 이유로 별개 빈이다.
 *
 * <p><b>실패 기록도 별개 트랜잭션이다.</b> {@link #process} 가 던지고 나면 그 트랜잭션은
 * 롤백되므로, 같은 트랜잭션에서 실패를 쓰면 기록까지 사라져 건이 {@code PROCESSING} 으로 남는다.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.validation-pdf", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class ValidationPdfProcessor {

    private final EstimateValidationReportRepository reportRepository;
    private final EstimateValidationRepository validationRepository;
    private final EstimateValidationService validationService;
    private final ReportNarrativeGenerator narrativeGenerator;
    private final ValidationPdfAssembler assembler;

    /**
     * 생성기는 워커와 같은 프로퍼티로 켜지므로 워커가 살아 있으면 이것도 있다.
     * (폰트가 없으면 이 빈의 생성이 실패해 기동에서 걸린다 — 한글이 빈 네모로 나오는 PDF 가
     * 조용히 나가는 것보다 낫다.)
     */
    private final PdfGenerationPort pdfGenerationPort;

    /**
     * 저장소는 <b>버킷 설정</b>으로 따로 켜지므로 워커가 살아 있어도 없을 수 있다.
     *
     * <p><b>없다고 기동을 실패시키지 않는다.</b> 그러면 차량·사고 API 까지 함께 죽는다.
     * 대신 <b>조용히 실패하지도 않는다</b> — 건마다 명확한 사유로 {@code FAILED} 를 남겨
     * 로그와 {@code failure_reason} 에서 원인을 찾을 수 있게 한다. {@code retry_count} 상한이
     * 있어 무한히 재시도되지도 않는다.
     */
    private final java.util.Optional<DocumentStoragePort> storagePort;

    /**
     * 큐에서 한 건을 선점한다.
     *
     * <p><b>워커가 아니라 여기 있는 이유</b> — 워커 안에 두고 {@code this.claim(...)} 으로 부르면
     * 프록시를 거치지 않아 {@code @Transactional} 이 먹지 않고, {@code @Modifying} 쿼리가
     * 트랜잭션 없이 실행되어 터진다.
     *
     * @return 내가 선점했으면 true
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(Long validationId) {
        return reportRepository.claimQueued(validationId, EstimateValidationReport.MAX_RETRY_COUNT) == 1;
    }

    /**
     * @throws RuntimeException 이 건만 실패. 워커가 받아 {@link #markFailed} 로 기록한다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(Long validationId) {
        EstimateValidation validation = validationRepository.findById(validationId)
                .orElseThrow(() -> new IllegalStateException("선점한 검증이 사라졌다: " + validationId));

        // 미완료 검증의 PDF 는 담을 내용이 없다 — 등급도 항목도 아직 정해지지 않았다.
        if (validation.getStatus() != ValidationStatus.COMPLETED) {
            throw new IllegalStateException(
                    "검증이 완료되지 않아 PDF 를 만들 수 없다: " + validationId
                            + " (상태 " + validation.getStatus() + ")");
        }

        // 소유자 검사를 거치지 않는 내부 경로다. 워커는 큐에서 집은 건을 처리하므로
        // 요청자가 없다 — 그래서 memberId 를 엔티티에서 그대로 읽는다.
        ValidationResultResponse result = validationService.result(validation.getMemberId(), validationId);

        // ① LLM 은 문장만 만든다. 실패해도 PDF 생성을 멈추지 않는다.
        ReportNarrative narrative = narrativeGenerator.generate(result);
        Instant generatedAt = Instant.now();

        // ②③ 서버가 HTML 을 만들고 PDF 로 렌더링한다.
        PdfGenerationPort.GeneratedPdf pdf =
                pdfGenerationPort.generate(assembler.assemble(result, narrative, generatedAt));

        // ④ 저장소에 넣고 키를 리포트에 기록한다.
        DocumentStoragePort.StoredDocument stored = storagePort
                .orElseThrow(() -> new MissingDocumentStorageException(validationId))
                .storeReport(validationId, pdf.content());

        EstimateValidationReport report = reportRepository.findById(validationId)
                .orElseThrow(() -> new IllegalStateException("리포트 행이 사라졌다: " + validationId));
        report.markCompleted(stored.storageKey(), generatedAt);

        log.info("검증 PDF 완료: validationId={}, {}bytes, LLM문장={}",
                validationId, pdf.size(), narrative.isEmpty() ? "없음(템플릿만)" : "있음");
    }

    /**
     * 실패를 기록한다. <b>{@link #process} 와 다른 트랜잭션이어야 한다.</b>
     *
     * <p>재시도 여지가 남아 있으면 큐로 되돌리고, 상한에 닿았으면 실패로 종결한다.
     * {@code reason} 은 사용자에게 보일 수 있는 분류 문구여야 한다 — 내부 예외 메시지·스택·
     * 저장소 키를 넣지 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long validationId, String reason) {
        reportRepository.findById(validationId).ifPresent(report -> {
            if (report.getStatus() != ValidationStatus.PROCESSING) return;
            if (report.canRetry()) {
                report.returnToQueue();
                log.warn("검증 PDF 생성 실패 — 큐로 되돌린다: validationId={}, 시도={}/{}",
                        validationId, report.getRetryCount(), EstimateValidationReport.MAX_RETRY_COUNT);
                return;
            }
            report.markFailed(reason, Instant.now());
            log.error("검증 PDF 생성 실패 — 재시도 상한에 도달해 종결한다: validationId={}", validationId);
        });
    }

    /**
     * 큐에 남았지만 재시도 상한에 닿아 아무도 집을 수 없는 건을 종결한다.
     *
     * <p>{@code claimQueued} 가 상한 조건 때문에 이 건들을 영영 집지 못하므로 그대로 두면
     * 매 주기 후보로만 조회된다. {@code QUEUED} 에서 바로 {@code FAILED} 로 갈 수 없으므로
     * (엔티티가 전이를 강제한다) 한 번 선점한 뒤 종결한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abandonExhausted(Long validationId, String reason) {
        reportRepository.findById(validationId).ifPresent(report -> {
            if (report.getStatus() != ValidationStatus.QUEUED || report.canRetry()) return;
            // 상한에 닿은 건이라 retry_count 를 더 올리지 않고 바로 종결한다.
            // PROCESSING 을 거치면 retry_count 가 4가 되어 ck_evr_retry 를 위반한다.
            report.abandon(reason, Instant.now());
            log.error("검증 PDF 재시도 상한 도달 — 큐에서 제거하고 종결한다: validationId={}", validationId);
        });
    }

    /**
     * 문서 저장소가 구성되지 않았다.
     *
     * <p>별도 타입인 이유는 <b>사용자에게 보일 사유가 다르기 때문</b>이다. 렌더링 실패는
     * "잠시 후 다시" 지만 이것은 설정 문제라 다시 시도해도 같다 — 운영자가 봐야 한다.
     */
    static class MissingDocumentStorageException extends IllegalStateException {
        MissingDocumentStorageException(Long validationId) {
            super("문서 저장소가 구성되지 않아 PDF 를 보관할 수 없다: " + validationId);
        }
    }
}
