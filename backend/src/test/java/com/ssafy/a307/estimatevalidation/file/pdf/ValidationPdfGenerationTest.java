package com.ssafy.a307.estimatevalidation.file.pdf;

import com.ssafy.a307.estimatevalidation.file.PdfGenerationPort;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>PDF 를 실제로 만들어 검증한다.</b> 목으로 대체하지 않는다 —
 * 한글 깨짐은 실제 생성 없이는 발견되지 않는 종류의 결함이고, 눈으로 열어 보지 않으면
 * 조용히 넘어간다. 그래서 생성한 PDF 에서 <b>텍스트를 추출해</b> 단언한다.
 *
 * <p>PDFBox 는 Open HTML to PDF 가 이미 끌어오므로 추출에 새 의존성이 필요 없다.
 *
 * <p>{@code app.validation-pdf.enabled=true} 로 생성기 빈을 띄우되, <b>워커는 이 테스트에서
 * 돌지 않는다</b> — {@code initial-delay} 를 1시간으로 밀어 스케줄러가 스스로 큐를 건드리지
 * 않게 했다.
 */
@SpringBootTest(properties = {
        "app.validation-pdf.enabled=true",
        "app.validation-pdf.poll-interval=PT1H",
        "app.validation-pdf.initial-delay=PT1H",
        "app.validation-pdf.batch-size=3",
        "app.validation-pdf.processing-timeout=PT10M"
})
@DisplayName("검증 결과 PDF 생성")
class ValidationPdfGenerationTest {

    private static final String LEGAL_NOTICE =
            "이 결과는 AI와 사례 통계를 이용한 참고용 추정치이며 실제 수리비와 다를 수 있고 특정 사업자를 평가하지 않습니다.";

    @Autowired
    private PdfGenerationPort pdfGenerationPort;

    // ------------------------------------------------------------------ 기본

    @Test
    @DisplayName("유효한 PDF 바이트를 만든다")
    void producesValidPdfBytes() {
        PdfGenerationPort.GeneratedPdf pdf = pdfGenerationPort.generate(report(items(3), aiAttached()));

        byte[] content = pdf.content();
        assertThat(content).isNotEmpty();
        assertThat(new String(content, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("한글이 깨지지 않는다 — 추출한 텍스트에 한글이 그대로 나온다")
    void koreanTextSurvivesRendering() throws IOException {
        PdfGenerationPort.GeneratedPdf pdf = pdfGenerationPort.generate(report(items(3), aiAttached()));

        String text = extractText(pdf.content());

        // 폰트가 없거나 잘못 등록되면 이 글자들이 빈 네모(또는 공백)로 바뀐다.
        assertThat(text).contains(normalized("견적 검증 결과"));
        assertThat(text).contains(normalized("정비소 견적 총액"));
        assertThat(text).contains(normalized("프론트 펜더"));
        assertThat(text).contains(normalized("판금"));
        assertThat(text).contains(normalized("확인 권장"));
        assertThat(text).contains(normalized("정비소에 확인할 사항"));
    }

    @Test
    @DisplayName("고지 문구가 PDF 에 실린다")
    void legalNoticeIsPrinted() throws IOException {
        PdfGenerationPort.GeneratedPdf pdf = pdfGenerationPort.generate(report(items(2), aiAttached()));

        // 줄바꿈이 섞이므로 특징적인 구절로 확인한다.
        assertThat(extractText(pdf.content())).contains(normalized(LEGAL_NOTICE));
    }

    @Test
    @DisplayName("등급과 요약이 실린다")
    void gradeAndSummaryArePrinted() throws IOException {
        String text = extractText(pdfGenerationPort.generate(report(items(2), aiAttached())).content());

        assertThat(text).contains(normalized("확인 필요"));
        assertThat(text).contains(normalized("총 2개 항목 중 1개 항목의 확인을 권장합니다"));
    }

    // ------------------------------------------------------------------ 없는 값을 지어내지 않는다

    @Nested
    @DisplayName("없는 값을 지어내지 않는다")
    class NoFabrication {

        @Test
        @DisplayName("AI 예상 견적이 없으면 0 이 아니라 비교하지 않았다고 적는다")
        void missingAiEstimateIsStatedNotZeroed() throws IOException {
            PdfGenerationPort.PdfReport report = new PdfGenerationPort.PdfReport(
                    77L, "CAUTION", "주의", "총 1개 항목 중 1개 항목의 확인을 권장합니다.", null,
                    150_000L,
                    null, null, null, null,   // ← AI 예상 견적 전부 없음
                    1, 1, items(1), List.of(), LEGAL_NOTICE, Instant.now());

            String text = extractText(pdfGenerationPort.generate(report).content());

            assertThat(text).contains(normalized("분석이 연결되지 않아 비교하지 않았습니다"));
            assertThat(text).doesNotContain(normalized("AI 예상 견적 범위"));
            assertThat(text).doesNotContain(normalized("중앙값과의 차액"));
        }

        @Test
        @DisplayName("참조 통계가 없는 항목은 0 이 아니라 자료 없음이다")
        void missingReferenceIsStated() throws IOException {
            PdfGenerationPort.PdfLineItem noReference = new PdfGenerationPort.PdfLineItem(
                    1, "프론트 펜더", "판금", 1, 150_000L,
                    null, null,                 // ← 참조 통계 없음
                    "범위 내", null, null);
            PdfGenerationPort.PdfReport report = new PdfGenerationPort.PdfReport(
                    78L, "APPROPRIATE", "적정", "총 1개 항목 중 0개 항목의 확인을 권장합니다.", null,
                    150_000L, null, null, null, null, 0, 1,
                    List.of(noReference), List.of(), LEGAL_NOTICE, Instant.now());

            String text = extractText(pdfGenerationPort.generate(report).content());

            assertThat(text).contains(normalized("자료 없음"));
        }
    }

    // ------------------------------------------------------------------ LLM 문장 취급

    @Nested
    @DisplayName("LLM 문장 취급")
    class LlmSentences {

        @Test
        @DisplayName("LLM 문장이 없어도 PDF 가 그대로 생성된다 — 템플릿 요약만으로 완성된다")
        void generatesWithoutLlmSentences() throws IOException {
            PdfGenerationPort.PdfReport report = report(items(2), aiAttached());   // gradeExplanation = null

            String text = extractText(pdfGenerationPort.generate(report).content());

            assertThat(text).contains(normalized("견적 검증 결과"));
            assertThat(text).contains(normalized("총 2개 항목 중 1개 항목의 확인을 권장합니다"));
        }

        @Test
        @DisplayName("LLM 문장에 태그가 섞여도 마크업으로 해석되지 않는다")
        void llmSentencesAreEscapedNotRendered() throws IOException {
            String injected = "<b>굵게</b> 그리고 <script>alert(1)</script> 확인이 필요합니다";
            PdfGenerationPort.PdfReport report = new PdfGenerationPort.PdfReport(
                    79L, "CAUTION", "주의", "총 1개 항목 중 1개 항목의 확인을 권장합니다.",
                    injected,
                    150_000L, null, null, null, null, 1, 1,
                    List.of(new PdfGenerationPort.PdfLineItem(
                            1, "프론트 펜더", "판금", 1, 150_000L, 120_000, 20,
                            "확인 권장", "소계가 유사 사례 75백분위를 초과합니다.",
                            "<i>기울임</i> 확인 권장")),
                    List.of(), LEGAL_NOTICE, Instant.now());

            String text = extractText(pdfGenerationPort.generate(report).content());

            // th:text 가 이스케이프하므로 태그가 "문자" 로 인쇄된다 — 마크업으로 사라지지 않는다.
            assertThat(text).contains(normalized("<b>굵게</b>"));
            assertThat(text).contains(normalized("<script>alert(1)</script>"));
            assertThat(text).contains(normalized("<i>기울임</i>"));
        }
    }

    // ------------------------------------------------------------------ 대량 항목

    @Test
    @DisplayName("항목 200개에서도 예외 없이 생성되고 마지막 행까지 실린다")
    void handlesMaximumItemCount() throws IOException {
        PdfGenerationPort.GeneratedPdf pdf =
                pdfGenerationPort.generate(report(items(200), aiAttached()));

        String text = extractText(pdf.content());
        assertThat(text).contains(normalized("품명 1"));
        // 한 페이지에 잘려 나가면 마지막 행이 없다.
        assertThat(text).contains(normalized("품명 200"));
        assertThat(pageCount(pdf.content())).isGreaterThan(1);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 추출한 텍스트에서 <b>공백을 전부 지운다.</b>
     *
     * <p>PDF 텍스트 추출은 표 셀 폭에 맞춰 문장 중간에 개행을 넣는다 — "소계 450,000\n원이".
     * 그것은 렌더링이 정상이라는 뜻이지 결함이 아니므로, 글자가 살아 있는지만 본다.
     * 한글이 깨지면 글자 자체가 사라지거나 빈 네모가 되므로 이 비교로도 잡힌다.
     */
    private static String extractText(byte[] pdf) throws IOException {
        return strip(raw(pdf));
    }

    /** 공백을 남긴 원문. 페이지 수·구조를 볼 때만 쓴다. */
    private static String raw(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    /**
     * 공백을 전부 지운다.
     *
     * <p>{@code (?U)} 가 필요하다 — PDF 추출 텍스트는 일반 공백이 아니라 <b>줄바꿈 없는
     * 공백(U+00A0) 같은 유니코드 공백</b>을 쓴다. Java 의 기본 {@code \s} 는 ASCII 공백만
     * 잡으므로 그것 없이는 공백이 그대로 남아 비교가 어긋난다. 날짜의 하이픈도 U+2011 이
     * 쓰이는데, 그건 단언 대상이 아니라 그대로 둔다.
     */
    private static String strip(String value) {
        return value.replaceAll("(?U)\\s+", "");
    }

    /** 기대 문자열도 <b>같은 함수로</b> 정규화한다. 둘이 다르면 비교가 어긋난다. */
    private static String normalized(String expected) {
        return strip(expected);
    }

    private static int pageCount(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            return document.getNumberOfPages();
        }
    }

    private static PdfGenerationPort.PdfReport report(
            List<PdfGenerationPort.PdfLineItem> items, boolean withAi) {
        int reviewCount = (int) items.stream()
                .filter(i -> "확인 권장".equals(i.decision())).count();
        return new PdfGenerationPort.PdfReport(
                42L, "CAUTION", "확인 필요",
                "확인 필요: 총 %d개 항목 중 %d개 항목의 확인을 권장합니다.".formatted(items.size(), reviewCount),
                null,
                1_250_000L,
                withAi ? 900_000 : null,
                withAi ? 1_000_000 : null,
                withAi ? 1_200_000 : null,
                withAi ? 250_000L : null,
                reviewCount, items.size(), items,
                List.of("프론트 펜더 판금 공임 산정 기준을 알려주실 수 있나요?",
                        "교환으로 산정한 부품의 재사용 가능 여부를 확인해 주실 수 있나요?"),
                LEGAL_NOTICE, Instant.now());
    }

    private static boolean aiAttached() {
        return true;
    }

    /** 첫 항목은 항상 "확인 권장" 이라 검증 문구가 PDF 에 나타난다. */
    private static List<PdfGenerationPort.PdfLineItem> items(int count) {
        List<PdfGenerationPort.PdfLineItem> items = new ArrayList<>(count);
        items.add(new PdfGenerationPort.PdfLineItem(
                1, "프론트 펜더", "판금", 1, 450_000L, 300_000, 20,
                "확인 권장", "소계 450,000원이 유사 사례 20건의 75백분위 300,000원을 초과합니다.", null));
        IntStream.rangeClosed(2, count).forEach(i -> items.add(new PdfGenerationPort.PdfLineItem(
                i, "품명 " + i, "교환", 1, 50_000L, 60_000, 12, "범위 내", null, null)));
        return items;
    }
}
