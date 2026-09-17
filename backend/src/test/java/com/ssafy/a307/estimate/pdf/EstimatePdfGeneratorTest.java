package com.ssafy.a307.estimate.pdf;

import com.ssafy.a307.estimate.dto.EstimateBasisResponse;
import com.ssafy.a307.estimate.dto.EstimateItemResponse;
import com.ssafy.a307.estimate.dto.EstimateReportResponse;
import com.ssafy.a307.estimate.dto.EstimateResponse;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 견적 PDF 실제 렌더링 (S15P21A307-340). 템플릿·폰트·이미지가 한 번에 맞물리는지 파일을 만들어 본다.
 *
 * <p>한글이 PDF 에서 <b>텍스트로 추출되는지</b>를 본다 — 폰트가 빠지면 글자가 빈 네모로 그려지고
 * 추출도 되지 않는다.
 */
@DisplayName("견적 PDF 렌더링")
class EstimatePdfGeneratorTest {

    /** 1×1 PNG. 오버레이 자리에 실제 이미지가 들어가는지 본다. */
    private static final String PNG_DATA_URI = "data:image/png;base64,"
            + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    private static EstimatePdfGenerator generator;

    @BeforeAll
    static void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        generator = new EstimatePdfGenerator(new EstimateReportHtmlRenderer(engine));
    }

    @Test
    @DisplayName("한글·번호·금액·고지 문구가 텍스트로 들어가고, 오버레이 없는 칸은 분석 이미지 없음이다")
    void rendersKoreanPdf() throws Exception {
        byte[] pdf = generator.generate(document(report(true)));

        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
        String text = extract(pdf);
        assertThat(text)
                .contains("예상 견적 리포트")
                .contains("R-20260911-0001")
                .contains("현대 아반떼")
                .contains("700,000 ~ 900,000원")
                .contains("분석 이미지 없음")
                .contains("참고용 추정치");
    }

    @Test
    @DisplayName("산정 불가 견적은 금액 대신 사유가 들어간다")
    void rendersNonEstimable() throws Exception {
        String text = extract(generator.generate(document(report(false))));

        assertThat(text).contains("산정 불가").contains("참조 사례 부족").doesNotContain("대표값");
    }

    /**
     * 추출한 텍스트의 공백·하이픈 변형을 일반 문자로 맞춘다. 렌더러가 줄바꿈 제어를 위해 하이픈을
     * U+2011(줄바꿈 없는 하이픈)로, 공백을 다른 공백 문자로 그려 추출 결과가 달라진다 — 화면 표시는 같다.
     */
    private static String extract(byte[] pdf) throws Exception {
        try (PDDocument doc = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(doc)
                    .replaceAll("\\p{Zs}", " ")
                    .replaceAll("[\\u2010\\u2011]", "-");
        }
    }

    private static EstimatePdfDocument document(EstimateReportResponse report) {
        return new EstimatePdfDocument("R-20260911-0001", report,
                List.of(new EstimatePdfDocument.Image(1L, "FRONT", PNG_DATA_URI),
                        new EstimatePdfDocument.Image(2L, "REAR", null)),
                Instant.parse("2026-09-11T03:00:00Z"));
    }

    private static EstimateReportResponse report(boolean estimable) {
        Instant now = Instant.parse("2026-09-11T03:00:00Z");
        List<EstimateItemResponse> items = estimable
                ? List.of(new EstimateItemResponse(1L, "FRONT_BUMPER", "앞 범퍼", "FRONT", "Crushed",
                        "exchange", "교환", new BigDecimal("1.50"), null, 92_000,
                        700_000, 800_000, 900_000, 12, false))
                : List.of();
        EstimateResponse estimate = estimable
                ? new EstimateResponse(1L, 1L, (short) 1, true, null, 10_000, new BigDecimal("1.50"),
                        700_000, 800_000, 900_000, 12, "HIGH", items, List.of(), now)
                : new EstimateResponse(1L, 1L, (short) 1, false, "참조 사례 부족", null, null,
                        null, null, null, null, null, items, List.of(), now);

        return new EstimateReportResponse(
                new EstimateReportResponse.Vehicle("현대", "아반떼", "SEDAN", "Compact", (short) 2020),
                new EstimateReportResponse.Accident(3L, now),
                List.of(),
                estimate,
                new EstimateBasisResponse(1L, (short) 1, List.of()),
                null,
                "이 결과는 AI와 사례 통계를 이용한 참고용 추정치이며 실제 수리비와 다를 수 있고 특정 사업자를 평가하지 않습니다.",
                // 안내 고지(S15P21A307-488)는 아직 PDF 가 그리지 않는다 — 실을 섹션이 없다.
                null,
                now);
    }
}
