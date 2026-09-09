package com.ssafy.a307.estimatevalidation.file.llm;

import tools.jackson.databind.JsonNode;
import com.ssafy.a307.estimatevalidation.config.EstimateOcrProperties;
import com.ssafy.a307.estimatevalidation.domain.ValidationGrade;
import com.ssafy.a307.estimatevalidation.entity.EstimateValidation;
import com.ssafy.a307.estimatevalidation.file.SummaryGenerationPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Locale;

/**
 * 검증 요약을 LLM 이 쓰게 하는 {@link SummaryGenerationPort} 구현.
 *
 * <p><b>모델이 판단하지 않는다.</b> 등급·총액·검토 권장 항목은 규칙 엔진이 이미 정했고
 * ({@code GradeDecider}·{@code EstimateValidationEngine}) 여기서 하는 일은 그 값을 문장으로
 * 옮기는 것뿐이다. 그래서 {@code SummaryRequest} 가 주는 것 밖의 정보를 넘기지 않는다.
 *
 * <p><b>실패하면 아무 일도 일어나지 않는다.</b> 예외를 그대로 던지면
 * {@code EstimateValidationService.applySummary} 가 받아 규칙 기반 템플릿 요약으로 되돌린다.
 * 요약은 이미 끝난 계산을 보기 좋게 쓰는 일이라, 그것 때문에 검증 전체가 실패하면 안 된다.
 *
 * <p><b>켜야만 뜬다.</b> {@code app.estimate-summary.enabled=true} 가 없으면 이 빈이 없고,
 * 요약은 지금까지처럼 템플릿이다. 접속 정보(기준 URL·모델·키)는 판독 어댑터와 같은
 * {@code app.estimate-ocr.*} 를 쓴다 — 같은 GMS 키로 같은 게이트웨이를 부르므로 설정을
 * 두 벌 두면 어긋나기만 한다.
 *
 * <p><b>크레딧을 쓴다.</b> 검증 한 건마다 판독 호출에 더해 한 번 더 부른다. 그래서 재시도하지
 * 않는다 — 실패하면 템플릿으로 조용히 돌아가면 되고, 요약 하나를 위해 팀 공용 크레딧을
 * 두 번 태울 이유가 없다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.estimate-summary", name = "enabled", havingValue = "true")
public class LlmSummaryGenerationAdapter implements SummaryGenerationPort {

    /** 요약은 짧다. 판독보다 훨씬 적게 잡아 크레딧을 아낀다. */
    private static final int MAX_OUTPUT_TOKENS = 1024;
    /** 지시문이 요구하는 300자보다 넉넉히 잡되, 모델이 장황해질 때를 대비해 잘라 둔다. */
    private static final int MAX_SUMMARY_LENGTH = 600;

    private final EstimateOcrProperties properties;
    private final LlmVendor vendor;
    private final RestClient restClient;

    public LlmSummaryGenerationAdapter(EstimateOcrProperties properties) {
        this.properties = properties;
        this.vendor = LlmVendors.all().stream()
                .filter(candidate -> candidate.name().equals(normalized(properties.provider())))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "요약 어댑터가 알 수 없는 공급자를 받았다: " + properties.provider()));
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory(properties))
                .build();
        log.info("검증 요약 어댑터: {} (model={})", vendor.name(), properties.model());
    }

    @Override
    public GeneratedSummary generate(SummaryRequest request) {
        String instruction = SummaryPromptFactory.instruction(request, gradeDisplayName(request.grade()));

        long startedAt = System.currentTimeMillis();
        JsonNode response = restClient.post()
                .uri(vendor.path(properties.model()))
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> vendor.headers(nullSafeKey()).forEach(headers::set))
                .body(vendor.textBody(properties.model(), instruction, MAX_OUTPUT_TOKENS))
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("요약 응답이 비어 있다");
        }
        String text = vendor.extractText(response);
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("요약 응답에서 본문을 찾지 못했다");
        }
        log.info("검증 요약 생성 완료: validationId={}, {}ms",
                request.validationId(), System.currentTimeMillis() - startedAt);
        return new GeneratedSummary(truncate(text.strip()), model());
    }

    /**
     * 등급 이름을 사용자용 표기로 바꾼다.
     *
     * <p>모델에 {@code APPROPRIATE} 같은 enum 이름을 그대로 넘기면 그것을 번역해 문장에 넣는다.
     * 화면 표기는 이미 {@code ValidationGrade.displayName()} 이 정해 두었으므로 그 값을 준다 —
     * 같은 개념에 두 가지 한국어 표기가 생기지 않게 한다.
     */
    private static String gradeDisplayName(String grade) {
        try {
            return ValidationGrade.valueOf(grade).displayName();
        } catch (IllegalArgumentException unknown) {
            return grade;
        }
    }

    /**
     * 모델 이름. {@code llm_model} 이 VARCHAR(50) 이라
     * {@code EstimateValidation.recordSummaryModel} 이 한 번 더 자르지만, 계약이
     * {@code GeneratedSummary} 에도 걸려 있어 여기서도 맞춰 둔다.
     */
    private String model() {
        String model = properties.model();
        return model.length() > EstimateValidation.MAX_MODEL_LENGTH
                ? model.substring(0, EstimateValidation.MAX_MODEL_LENGTH) : model;
    }

    private static String truncate(String text) {
        return text.length() > MAX_SUMMARY_LENGTH ? text.substring(0, MAX_SUMMARY_LENGTH) : text;
    }

    private String nullSafeKey() {
        return properties.hasApiKey() ? properties.apiKey() : "";
    }

    private static String normalized(String provider) {
        return provider == null ? "" : provider.strip().toLowerCase(Locale.ROOT);
    }

    private static ClientHttpRequestFactory requestFactory(EstimateOcrProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.timeout());
        factory.setReadTimeout(properties.timeout());
        return factory;
    }
}
