package com.ssafy.a307.estimate.pdf;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 견적 PDF 의 HTML. {@code ValidationReportHtmlRenderer} 와 같은 원칙 — 서버가 템플릿으로 만들고,
 * 템플릿은 {@code th:text} 만 쓰며, 결과는 Open HTML to PDF 가 읽는 XHTML 이어야 한다.
 */
@Component
@RequiredArgsConstructor
public class EstimateReportHtmlRenderer {

    static final String TEMPLATE_NAME = "estimate-report";

    /** 사용자에게 보이는 시각은 한국 시간이다. */
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.KOREA);

    private final ITemplateEngine templateEngine;

    public String render(EstimatePdfDocument document) {
        Context context = new Context(Locale.KOREA);
        context.setVariable("doc", document);
        context.setVariable("report", document.report());
        context.setVariable("generatedAt", format(document.generatedAt()));
        context.setVariable("accidentAt", format(document.report().accident().createdAt()));
        return templateEngine.process(TEMPLATE_NAME, context);
    }

    private static String format(Instant at) {
        return at == null ? "-" : DISPLAY.format(at.atZone(DISPLAY_ZONE));
    }
}
