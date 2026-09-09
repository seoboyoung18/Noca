package com.ssafy.a307.estimatevalidation.file.pdf;

import com.ssafy.a307.estimatevalidation.dto.ValidationQuestionResponse;
import com.ssafy.a307.estimatevalidation.dto.ValidationResultResponse;
import com.ssafy.a307.estimatevalidation.file.PdfGenerationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 확정된 검증 결과와 LLM 문장을 합쳐 {@link PdfGenerationPort.PdfReport} 로 만든다.
 *
 * <p><b>여기가 "무엇을 서버가 정하고 무엇을 LLM 이 정하는가" 의 경계다.</b>
 * 숫자·등급·판정은 {@code ValidationResultResponse} 에서 그대로 옮기고, LLM 문장은
 * {@code gradeExplanation} 과 항목별 {@code note} 두 자리에만 넣는다.
 * <b>이 클래스는 계산을 하나도 하지 않는다</b> — 차액도 이미 계산돼 들어온다.
 *
 * <p>고지 문구는 {@code ValidationResultResponse.legalNotice} 를 그대로 쓴다. 그 값은
 * {@code EstimateValidationService.LEGAL_NOTICE} 상수에서 온다 — 화면과 PDF 가 다른 문구를
 * 쓰면 안 되므로 여기서 새로 쓰지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ValidationPdfAssembler {

    public PdfGenerationPort.PdfReport assemble(
            ValidationResultResponse result, ReportNarrative narrative, Instant generatedAt) {

        List<PdfGenerationPort.PdfLineItem> items = result.items().stream()
                .map(item -> toLineItem(item, narrative))
                .toList();

        List<String> questions = result.questions().stream()
                .map(ValidationQuestionResponse::text)
                .filter(text -> text != null && !text.isBlank())
                .toList();

        return new PdfGenerationPort.PdfReport(
                result.validationId(),
                result.grade().name(),
                result.gradeDisplayName(),
                result.summary(),
                narrative.gradeExplanation(),
                // claimedTotal 이 null 인 검증은 완료될 수 없지만(파일 경로도 완료 시 채운다)
                // 포트가 long 을 요구하므로 방어적으로 0 을 쓴다. 이 경우 항목도 비어 있다.
                result.claimedTotal() == null ? 0L : result.claimedTotal(),
                result.aiTotalMin(),
                result.aiTotalMedian(),
                result.aiTotalMax(),
                result.differenceFromMedian(),
                result.reviewItemCount(),
                result.totalItemCount(),
                items,
                questions,
                result.legalNotice(),
                generatedAt);
    }

    private static PdfGenerationPort.PdfLineItem toLineItem(
            ValidationResultResponse.Item item, ReportNarrative narrative) {
        return new PdfGenerationPort.PdfLineItem(
                item.lineNo(),
                item.rawItemName(),
                item.workType(),
                item.quantity(),
                item.subtotal() == null ? 0L : item.subtotal(),
                item.referenceP75(),
                item.referenceCaseCount(),
                // "범위 내" 또는 "확인 권장". 이 서비스가 쓸 수 있는 표현의 한계다.
                item.displayDecision(),
                item.reason(),
                narrative.noteFor(item.lineNo()));
    }
}
