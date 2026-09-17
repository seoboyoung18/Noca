package com.ssafy.a307.estimate.pdf;

import com.ssafy.a307.estimate.dto.EstimateBasisResponse;
import com.ssafy.a307.estimate.dto.EstimateItemResponse;
import com.ssafy.a307.estimate.dto.EstimateReportResponse;
import com.ssafy.a307.estimate.dto.EstimateResponse;
import com.ssafy.a307.repairchecklist.entity.RepairChecklistStatus;
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

    /** 운영 시드(2026-09-15-guidance-notice.sql)와 같은 문장. */
    private static final String GUIDANCE = "본 체크리스트와 질문은 AI 분석 결과에 기반한 참고용 안내이며, "
            + "실제 정비 범위와 방식은 정비 전문가의 점검 결과에 따라 달라질 수 있습니다";

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

    @Test
    @DisplayName("체크리스트 항목이 있으면 정비 체크리스트 섹션과 안내 고지가 들어간다")
    void rendersChecklistWithGuidance() throws Exception {
        EstimateReportResponse.Checklist checklist = new EstimateReportResponse.Checklist(
                RepairChecklistStatus.COMPLETED, (short) 2, Instant.parse("2026-09-11T02:00:00Z"),
                List.of(new EstimateReportResponse.ChecklistItem(11L, "앞 범퍼 교환 대신 판금이 가능한지 확인"),
                        new EstimateReportResponse.ChecklistItem(12L, "도장 색상 맞춤 범위 확인")));

        String text = extract(generator.generate(document(report(true, checklist, GUIDANCE))));

        assertThat(text)
                .contains("정비 체크리스트")
                .contains("앞 범퍼 교환 대신 판금이 가능한지 확인")
                .contains("도장 색상 맞춤 범위 확인")
                .contains("AI 분석 결과에 기반한 참고용 안내");
    }

    /** PDF 는 한 시점에 굳는 파일이다. "생성 중" 을 인쇄해 남기지 않고, 붙을 섹션이 없으면 안내 고지도 없다. */
    @Test
    @DisplayName("체크리스트 항목이 없으면(생성 중·실패·요청 전) 섹션도 안내 고지도 그리지 않는다")
    void omitsChecklistSectionWithoutItems() throws Exception {
        EstimateReportResponse.Checklist processing = new EstimateReportResponse.Checklist(
                RepairChecklistStatus.PROCESSING, (short) 1, null, List.of());

        String text = extract(generator.generate(document(report(true, processing, GUIDANCE))));

        assertThat(text)
                .doesNotContain("정비 체크리스트")
                .doesNotContain("AI 분석 결과에 기반한 참고용 안내")
                .contains("참고용 추정치");
    }

    /**
     * 체크리스트 자리가 {@code null} 로 들어와도 렌더링이 터지지 않는다. 템플릿은
     * {@code report.checklist().items()} 를 부르므로 {@code null} 이 그대로 닿으면 SpEL 이 NPE 로 죽고,
     * PDF 는 재시도 끝에 {@code FAILED} 로 굳는다. 응답의 compact 생성자가 빈 상태로 채운다.
     */
    @Test
    @DisplayName("체크리스트가 null 로 들어와도 PDF 가 만들어지고 섹션은 없다")
    void rendersWhenChecklistIsNull() throws Exception {
        byte[] pdf = generator.generate(document(report(true, null, GUIDANCE)));

        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
        assertThat(extract(pdf)).doesNotContain("정비 체크리스트").contains("참고용 추정치");
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
        return report(estimable, EstimateReportResponse.Checklist.NOT_REQUESTED, null);
    }

    private static EstimateReportResponse report(boolean estimable,
                                                 EstimateReportResponse.Checklist checklist,
                                                 String guidanceNotice) {
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
                checklist,
                "이 결과는 AI와 사례 통계를 이용한 참고용 추정치이며 실제 수리비와 다를 수 있고 특정 사업자를 평가하지 않습니다.",
                guidanceNotice,
                now);
    }
}
