package com.ssafy.a307.estimatevalidation.file.pdf;

import com.ssafy.a307.estimatevalidation.file.PdfGenerationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 파이프라인 ② — 서버가 HTML 을 만든다.
 *
 * <p><b>LLM 에게 마크업을 맡기지 않는 이유</b>는 둘이다. 첫째, Gemini 구조화 출력의
 * {@code mime_type} 은 {@code application/json} 만 지원돼 HTML 은 스키마를 강제할 수 없다.
 * 둘째, PDF 는 사용자가 정비소에 들고 갈 문서라 페이지 구성·표 구조·고지 문구 위치를
 * 서버가 통제해야 한다.
 *
 * <p><b>템플릿은 {@code th:text} 만 쓴다.</b> {@code th:utext} 는 이스케이프하지 않으므로
 * LLM 문장에 태그가 섞이면 그대로 마크업이 된다. Open HTML to PDF 는 브라우저가 아니지만
 * "신뢰할 수 없는 문자열을 마크업으로 해석시키지 않는다" 는 원칙은 같다.
 *
 * <p>렌더링 결과는 <b>유효한 XHTML</b> 이어야 한다 — Open HTML to PDF 가 XML 파서를 쓴다.
 * Thymeleaf 의 HTML 모드가 빈 태그를 닫고 속성을 인용해 주지만, 템플릿 쪽에서도
 * {@code <meta ... />} 처럼 자기 닫음을 지켰다.
 */
@Component
@RequiredArgsConstructor
public class ValidationReportHtmlRenderer {

    static final String TEMPLATE_NAME = "validation-report";

    /** 사용자에게 보이는 시각은 한국 시간이다. 서비스와 정비소가 국내에 있다. */
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter GENERATED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.KOREA);

    private final ITemplateEngine templateEngine;

    public String render(PdfGenerationPort.PdfReport report) {
        Context context = new Context(Locale.KOREA);
        context.setVariable("report", report);
        context.setVariable("generatedAt",
                GENERATED_AT.format(report.generatedAt().atZone(DISPLAY_ZONE)));
        return templateEngine.process(TEMPLATE_NAME, context);
    }
}
