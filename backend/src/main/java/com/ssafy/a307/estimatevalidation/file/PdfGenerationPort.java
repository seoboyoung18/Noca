package com.ssafy.a307.estimatevalidation.file;

import java.time.Instant;
import java.util.List;

/**
 * 검증 결과 PDF 생성 경계.
 *
 * <p><b>이 포트에 들어오는 값은 전부 서버가 확정한 것이다.</b> 등급은 {@code GradeDecider} 가
 * 규칙으로 정했고 숫자는 검증 엔진이 계산했다. LLM 이 만든 것은 {@code gradeExplanation} 과
 * 항목별 {@code note} 뿐이며, 그것도 <b>이미 확정된 값을 문장으로 옮긴 것</b>이다.
 * 이 구분이 흐려지면 지어낸 금액이 그대로 협상 근거가 된다.
 *
 * <p><b>{@code PdfReport} 를 넓혔다.</b> 원래는 등급·요약·총액·항목·고지 문구만 받았는데
 * 그것만으로는 화면과 같은 내용을 담을 수 없다 — AI 예상 범위와 차액이 없으면 "정비소 견적이
 * 비싼지" 를 PDF 만 보고 알 수 없고, 정비소 확인 질문이 없으면 사용자가 무엇을 물어야 할지
 * 모른다. 넓힌 필드는 전부 {@code ValidationResultResponse} 에 이미 있던 값이고
 * <b>새로 계산한 것이 하나도 없다.</b>
 *
 * <p><b>구현체는 프로퍼티로만 붙는다.</b> 폰트나 라이브러리가 없는 환경에서 빈이 뜨지 않게 하고,
 * 소비자는 {@code Optional<PdfGenerationPort>} 로 받아 없으면 기존 동작을 유지한다.
 */
public interface PdfGenerationPort {

    GeneratedPdf generate(PdfReport report);

    /**
     * @param gradeExplanation LLM 이 쓴 등급 설명. <b>없으면 null</b> — 템플릿 요약만 실린다
     * @param aiTotalMin       AI 예상 범위. <b>없으면 null 이고 0 으로 바꾸지 않는다</b>
     * @param questions        정비소에 물어볼 질문. 검증 완료 시 저장된 스냅샷이다
     */
    record PdfReport(
            long validationId,
            String grade,
            String gradeDisplayName,
            String summary,
            String gradeExplanation,
            long claimedTotal,
            Integer aiTotalMin,
            Integer aiTotalMedian,
            Integer aiTotalMax,
            Long differenceFromMedian,
            int reviewItemCount,
            int totalItemCount,
            List<PdfLineItem> items,
            List<String> questions,
            String disclaimer,
            Instant generatedAt
    ) {
        public PdfReport {
            if (validationId < 1) throw new IllegalArgumentException("validationId must be positive");
            if (grade == null || grade.isBlank()) throw new IllegalArgumentException("grade is required");
            if (summary == null || summary.isBlank()) throw new IllegalArgumentException("summary is required");
            if (claimedTotal < 0) throw new IllegalArgumentException("claimedTotal must not be negative");
            items = items == null ? List.of() : List.copyOf(items);
            questions = questions == null ? List.of() : List.copyOf(questions);
            if (disclaimer == null || disclaimer.isBlank()) {
                throw new IllegalArgumentException("disclaimer is required");
            }
            if (generatedAt == null) throw new IllegalArgumentException("generatedAt is required");
            if (gradeDisplayName == null || gradeDisplayName.isBlank()) gradeDisplayName = grade;
        }

        /** AI 예상 견적이 붙어 있는가. 없으면 템플릿이 "비교하지 않았습니다" 를 적는다. */
        public boolean hasAiEstimate() {
            return aiTotalMedian != null;
        }
    }

    /**
     * @param decision {@code displayDecision} — "범위 내" 또는 "확인 권장". <b>여기까지가 표현의 한계다</b>
     * @param reason   규칙 엔진이 만든 사유. LLM 이 만든 것이 아니다
     * @param note     LLM 이 쓴 보충 문장. 없으면 null
     */
    record PdfLineItem(
            int lineNo,
            String itemName,
            String workType,
            long quantity,
            long subtotal,
            Integer referenceP75,
            Integer referenceCaseCount,
            String decision,
            String reason,
            String note
    ) {
        public PdfLineItem {
            if (lineNo < 1) throw new IllegalArgumentException("lineNo must be positive");
            if (itemName == null || itemName.isBlank()) throw new IllegalArgumentException("itemName is required");
            if (subtotal < 0) throw new IllegalArgumentException("subtotal must not be negative");
            if (decision == null || decision.isBlank()) throw new IllegalArgumentException("decision is required");
        }
    }

    record GeneratedPdf(byte[] content) {
        public GeneratedPdf {
            content = content == null ? null : content.clone();
            if (content == null || content.length == 0) {
                throw new IllegalArgumentException("content must not be empty");
            }
        }

        @Override
        public byte[] content() {
            return content.clone();
        }

        public int size() {
            return content.length;
        }

        /** 바이트를 {@code toString} 에 담지 않는다. */
        @Override
        public String toString() {
            return "GeneratedPdf[bytes=" + content.length + "]";
        }
    }
}
