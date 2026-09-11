package com.ssafy.a307.estimate.pdf;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.ssafy.a307.estimatevalidation.file.pdf.KoreanPdfFont;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;

/**
 * HTML 을 PDF 바이트로 만든다 (S15P21A307-340).
 *
 * <p>라이브러리와 폰트는 검증 PDF 가 들여온 것을 그대로 쓴다({@link KoreanPdfFont} 호출만, 수정 없음).
 * 검증 PDF 생성기({@code OpenHtmlValidationPdfGenerator})는 입력이 검증 전용이라 재사용하지 않는다.
 *
 * <p><b>워커와 같은 프로퍼티로 켠다.</b> 폰트를 기동 시 1회 풀어 두고, 없으면 이 빈의 생성이 실패한다 —
 * 한글이 빈 네모로 나오는 PDF 가 조용히 나가는 것보다 기동에서 걸리는 편이 낫다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.estimate-pdf", name = "enabled", havingValue = "true")
public class EstimatePdfGenerator {

    private final EstimateReportHtmlRenderer htmlRenderer;
    private final Path fontFile;

    public EstimatePdfGenerator(EstimateReportHtmlRenderer htmlRenderer) {
        this.htmlRenderer = htmlRenderer;
        this.fontFile = KoreanPdfFont.extractToTempFile();
    }

    /**
     * @throws IllegalStateException 렌더링 실패. 워커가 받아 재시도 대상으로 기록한다
     */
    public byte[] generate(EstimatePdfDocument document) {
        long startedAt = System.currentTimeMillis();
        String html = htmlRenderer.render(document);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            // 템플릿의 font-family: 'korean' 과 이름이 같아야 한다.
            builder.useFont(fontFile.toFile(), KoreanPdfFont.FAMILY);
            // 이미지는 data URI 로 박혀 있어 외부 리소스를 찾지 않는다.
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();

            byte[] content = out.toByteArray();
            log.info("견적 PDF 생성: reportNo={}, 이미지 {}장, {}bytes, {}ms",
                    document.reportNo(), document.images().size(), content.length,
                    System.currentTimeMillis() - startedAt);
            return content;
        } catch (Exception e) {
            // HTML 본문에는 수리비가 들어 있어 로그에 남기지 않는다.
            throw new IllegalStateException("견적 PDF 렌더링 실패: " + document.reportNo(), e);
        }
    }
}
