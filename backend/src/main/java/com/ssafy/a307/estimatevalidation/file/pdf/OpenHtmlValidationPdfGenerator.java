package com.ssafy.a307.estimatevalidation.file.pdf;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.estimatevalidation.file.PdfGenerationPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;

/**
 * 파이프라인 ③ — HTML 을 PDF 바이트로 만든다. {@link PdfGenerationPort} 의 유일한 구현이다.
 *
 * <p><b>프로퍼티로 켠다.</b> {@code app.validation-pdf.enabled=true} 가 아니면 이 빈이 뜨지 않고,
 * 소비자는 {@code Optional<PdfGenerationPort>} 가 빈 것을 보고 기존 동작을 유지한다 —
 * 폰트나 라이브러리가 없는 환경에서 기동이 실패하면 차량·사고 API 까지 함께 죽는다.
 *
 * <p><b>폰트를 기동 시 1회 준비한다.</b> 요청마다 6MB 를 다시 푸는 것을 피한다
 * ({@link KoreanPdfFont}). 폰트가 없으면 <b>이 빈의 생성이 실패한다</b> — 한글이 빈 네모로
 * 나오는 PDF 가 조용히 나가는 것보다 기동에서 걸리는 편이 낫다.
 *
 * <p><b>이 클래스는 내용을 만들지 않는다.</b> 등급·숫자·판정은 이미 확정돼 들어오고, 문장도
 * 밖에서 정해진다. 여기서 하는 일은 HTML 을 종이 크기에 맞춰 그리는 것뿐이다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.validation-pdf", name = "enabled", havingValue = "true")
public class OpenHtmlValidationPdfGenerator implements PdfGenerationPort {

    private final ValidationReportHtmlRenderer htmlRenderer;
    private final Path fontFile;

    public OpenHtmlValidationPdfGenerator(ValidationReportHtmlRenderer htmlRenderer) {
        this.htmlRenderer = htmlRenderer;
        this.fontFile = KoreanPdfFont.extractToTempFile();
        log.info("검증 결과 PDF 생성기 준비 완료 (폰트={})", KoreanPdfFont.CLASSPATH_LOCATION);
    }

    @Override
    public GeneratedPdf generate(PdfReport report) {
        long startedAt = System.currentTimeMillis();
        String html = htmlRenderer.render(report);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            // CSS 의 font-family: 'korean' 과 이름이 같아야 한다.
            builder.useFont(fontFile.toFile(), KoreanPdfFont.FAMILY);
            // baseUri 를 비워 둔다 — 템플릿이 외부 리소스를 참조하지 않으므로 상대 경로 해석이
            // 필요 없고, 값을 주면 렌더러가 그 경로를 뒤지려 든다.
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();

            byte[] content = out.toByteArray();
            log.info("검증 결과 PDF 생성 완료: validationId={}, 항목={}건, {}bytes, {}ms",
                    report.validationId(), report.items().size(), content.length,
                    System.currentTimeMillis() - startedAt);
            return new GeneratedPdf(content);
        } catch (Exception e) {
            // 렌더링 실패 원인은 로그에만 남긴다. HTML 본문에는 견적서 내용이 들어 있다.
            log.error("검증 결과 PDF 생성에 실패했다: validationId={}", report.validationId(), e);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "검증 결과 PDF를 만들지 못했습니다.");
        }
    }
}
