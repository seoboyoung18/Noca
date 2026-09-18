package com.ssafy.a307.estimate.pdf;

import com.ssafy.a307.estimate.dto.EstimateBasisResponse;
import com.ssafy.a307.estimate.dto.EstimateItemResponse;
import com.ssafy.a307.estimate.dto.EstimateReportResponse;
import com.ssafy.a307.estimate.dto.EstimateReportResponse.Narrative;
import com.ssafy.a307.estimate.dto.EstimateResponse;
import com.ssafy.a307.estimate.dto.UnresolvedPartResponse;
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
    @DisplayName("한글·번호·금액·고지 문구가 텍스트로 들어간다")
    void rendersKoreanPdf() throws Exception {
        byte[] pdf = generator.generate(document(report(true)));

        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
        String text = extract(pdf);
        assertThat(text)
                .contains("예상 견적 리포트")
                .contains("R-20260911-0001")
                .contains("현대 아반떼")
                .contains("700,000 ~ 900,000원")
                .contains("참고용 추정치");
    }

    /**
     * S15P21A307-547 이전에는 오버레이가 없으면 "분석 이미지 없음" 이 그려졌다. 오버레이가
     * 2026-09-11 에 폐기돼 그 문구가 <b>모든 리포트에 항상</b> 떴고, 사진 칸이 통째로 비었다.
     * 이제 그 자리에 사용자가 올린 사진이 들어간다 — 읽지도 못했을 때만 빈칸이다.
     */
    @Test
    @DisplayName("사진을 실었으면 분석 이미지 없음 문구가 나오지 않는다")
    void noPlaceholderWhenPhotoIsRendered() throws Exception {
        String text = extract(generator.generate(document(report(true))));

        assertThat(text).doesNotContain("분석 이미지 없음");
    }

    @Test
    @DisplayName("산정 불가 견적은 금액 대신 사유가 들어간다")
    void rendersNonEstimable() throws Exception {
        String text = extract(generator.generate(document(report(false))));

        assertThat(text).contains("산정 불가").contains("참조 사례 부족").doesNotContain("대표값");
    }

    /**
     * 부분 견적(S15P21A307-534). 총액이 산정한 항목만의 합이라, 빠진 부위를 밝히지 않으면 PDF 를
     * 받은 보험사·정비소가 전체 수리비로 읽는다. 사유 문구를 모르면 사유 없이라도 제외 사실은 남긴다.
     */
    @Test
    @DisplayName("부분 견적은 총액에서 뺀 부위를 부위마다 밝힌다")
    void rendersExcludedPartsOfPartialEstimate() throws Exception {
        String text = extract(generator.generate(document(report(true, List.of(
                new UnresolvedPartResponse("HEAD_LAMP_L", "헤드램프(좌)", "Crushed",
                        "INSUFFICIENT_CASES", "근거 사례 부족"),
                new UnresolvedPartResponse("SIDE_MIRROR_R", null, null, "NEW_REASON", null))))));

        assertThat(text)
                .contains("700,000 ~ 900,000원")
                .contains("헤드램프(좌): 근거 사례 부족 — 총액에서 제외했습니다")
                .contains("SIDE_MIRROR_R: 산정하지 못해 총액에서 제외했습니다")
                .doesNotContain("NEW_REASON")
                .doesNotContain("산정하지 못한 부위:");
    }

    @Test
    @DisplayName("빠진 부위가 없으면 제외 문장이 없다")
    void rendersNoExclusionWithoutUnresolvedParts() throws Exception {
        String text = extract(generator.generate(document(report(true))));

        assertThat(text).doesNotContain("총액에서 제외").doesNotContain("산정하지 못한 부위");
    }

    @Test
    @DisplayName("산정 불가 견적은 산정하지 못한 부위를 한 줄로 나열한다")
    void rendersUnresolvedPartsOfNonEstimable() throws Exception {
        String text = extract(generator.generate(document(report(false, List.of(
                new UnresolvedPartResponse("HEAD_LAMP_L", "헤드램프(좌)", "Crushed",
                        "INSUFFICIENT_CASES", "근거 사례 부족"),
                new UnresolvedPartResponse("REAR_BUMPER", "리어 범퍼", "Scratched",
                        "INSUFFICIENT_CASES", "근거 사례 부족"))))));

        assertThat(text)
                .contains("산정 불가")
                .contains("산정하지 못한 부위: 헤드램프(좌), 리어 범퍼")
                .doesNotContain("총액에서 제외");
    }

    @Test
    @DisplayName("요약이 있으면 문단과 확인 권장이 PDF 에 들어간다 (S15P21A307-537)")
    void rendersNarrative() throws Exception {
        Narrative narrative = new Narrative("앞 범퍼 교환이 중심인 사고입니다.",
                List.of("부품 등급을 확인하세요.", "작업 전후 사진을 요청하세요."));

        String text = extract(generator.generate(document(report(true, List.of(), narrative))));

        assertThat(text)
                .contains("앞 범퍼 교환이 중심인 사고입니다.")
                .contains("부품 등급을 확인하세요.")
                .contains("작업 전후 사진을 요청하세요.");
    }

    /**
     * 아직 만들지 않았거나 생성이 실패한 견적이다. <b>빈 제목만 남으면</b> "요약이 있어야
     * 하는데 실패했다" 처럼 보이므로 절 자체를 그리지 않는다.
     */
    @Test
    @DisplayName("요약이 없으면 요약 절 자체가 없다")
    void omitsNarrativeSection() throws Exception {
        String text = extract(generator.generate(document(report(true))));

        assertThat(text).doesNotContain("요약");
    }

    /**
     * 오버레이는 2026-09-11 에 폐기돼 키가 늘 비어 있다. 그 자리에 <b>사용자가 올린 사진</b>을
     * 싣는 것이 S15P21A307-547 이고, 캡션이 둘을 구분한다 — 파손 표시가 없는 사진을
     * "분석 이미지" 라고 부르면 없는 근거가 있는 것처럼 보인다.
     */
    @Test
    @DisplayName("오버레이가 아닌 사진은 캡션이 업로드한 사진이라고 밝힌다 (S15P21A307-547)")
    void marksUploadedPhoto() throws Exception {
        String text = extract(generator.generate(document(report(true))));

        assertThat(text)
                .contains("FRONT · 분석 표시 포함")
                .contains("REAR · 업로드한 사진")
                .doesNotContain("분석 이미지 없음");
    }

    /** 중앙값 하나만 보여 주면 "왜 이 금액인지" 를 알 수 없다 (S15P21A307-547). */
    @Test
    @DisplayName("금액 구성(부품·공임·도장)이 표에 들어간다")
    void rendersCostBreakdown() throws Exception {
        String text = extract(generator.generate(document(report(true))));

        assertThat(text)
                .contains("부품").contains("공임").contains("도장")
                .contains("87,900").contains("92,000").contains("33,200");
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
                List.of(new EstimatePdfDocument.Image(1L, "FRONT", PNG_DATA_URI, true),
                        new EstimatePdfDocument.Image(2L, "REAR", PNG_DATA_URI, false)),
                Instant.parse("2026-09-11T03:00:00Z"));
    }

    private static EstimateReportResponse report(boolean estimable) {
        return report(estimable, List.of());
    }

    private static EstimateReportResponse report(boolean estimable,
                                                 List<UnresolvedPartResponse> unresolvedParts) {
        return report(estimable, unresolvedParts, null);
    }

    private static EstimateReportResponse report(boolean estimable,
                                                 List<UnresolvedPartResponse> unresolvedParts,
                                                 Narrative narrative) {
        Instant now = Instant.parse("2026-09-11T03:00:00Z");
        List<EstimateItemResponse> items = estimable
                ? List.of(new EstimateItemResponse(1L, "FRONT_BUMPER", "앞 범퍼", "FRONT", "Crushed",
                        "exchange", "교환", new BigDecimal("1.50"), 87_900, 92_000, 33_200,
                        700_000, 800_000, 900_000, 12, false))
                : List.of();
        EstimateResponse estimate = estimable
                ? new EstimateResponse(1L, 1L, (short) 1, true, null, 10_000, new BigDecimal("1.50"),
                        700_000, 800_000, 900_000, 12, "HIGH", items, unresolvedParts, List.of(), now)
                : new EstimateResponse(1L, 1L, (short) 1, false, "참조 사례 부족", null, null,
                        null, null, null, null, null, items, unresolvedParts, List.of(), now);

        return new EstimateReportResponse(
                new EstimateReportResponse.Vehicle("현대", "아반떼", "SEDAN", "Compact", (short) 2020),
                new EstimateReportResponse.Accident(3L, now),
                List.of(),
                estimate,
                new EstimateBasisResponse(1L, (short) 1, List.of()),
                null,
                narrative,
                "이 결과는 AI와 사례 통계를 이용한 참고용 추정치이며 실제 수리비와 다를 수 있고 특정 사업자를 평가하지 않습니다.",
                now);
    }
}
