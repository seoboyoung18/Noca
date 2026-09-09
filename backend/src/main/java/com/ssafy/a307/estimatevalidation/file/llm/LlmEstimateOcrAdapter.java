package com.ssafy.a307.estimatevalidation.file.llm;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.estimatevalidation.config.EstimateOcrProperties;
import com.ssafy.a307.estimatevalidation.domain.EstimateFileType;
import com.ssafy.a307.estimatevalidation.file.DocumentStoragePort;
import com.ssafy.a307.estimatevalidation.file.EstimateOcrPort;
import com.ssafy.a307.estimatevalidation.file.UnsupportedDocumentFormatException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 견적서 항목을 <b>멀티모달 LLM</b>으로 읽는 {@link EstimateOcrPort} 구현. 호출은 SSAFY GMS
 * 프록시를 지난다.
 *
 * <p><b>이 클래스는 어댑터일 뿐이다.</b> 판정 로직을 하나도 갖고 있지 않다 — 읽은 항목은
 * 직접 입력과 똑같은 매핑·범위 검사를 거쳐 같은 파이프라인으로 들어간다. 모델이 냈다고
 * 검증을 느슨하게 하지 않는다.
 *
 * <p><b>벤더에 묶이지 않는다.</b> 경로와 인증 헤더까지 {@link LlmVendor} 가 갖고, 어느 구현을
 * 쓸지는 {@code app.estimate-ocr.provider} 가 정한다. 모델 이름과 키도 프로퍼티다.
 *
 * <p><b>크레딧이 유한하다.</b> GMS 키는 팀 공용이고 크레딧과 만료일이 있다. 그래서
 * 재시도 상한을 낮게 두고({@code max-attempts} 기본 2), <b>크레딧 소진·키 만료처럼 다시 불러도
 * 똑같이 실패할 오류는 재시도하지 않는다.</b> 실패한 요청으로 남은 크레딧을 태우면 안 된다.
 *
 * <p><b>개인정보</b> — 견적서에는 차주 이름·차량번호·연락처가 찍혀 있을 수 있고, 이 클래스는
 * 그것을 외부 API 로 보낸다. 그래서 <b>요청·응답 본문을 로그에 남기지 않는다.</b> 남기는 것은
 * 상태 코드·소요 시간·항목 수뿐이다. 전송 자체의 승인 여부는 코드가 아니라 팀·기관이 정할
 * 문제이며 answer35 6장에 올렸다.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${app.estimate-ocr.provider:}' != ''")
public class LlmEstimateOcrAdapter implements EstimateOcrPort {

    private final DocumentStoragePort storagePort;
    private final EstimateOcrProperties properties;
    private final LlmVendor vendor;
    private final OcrExtractionParser parser;
    private final RestClient restClient;

    public LlmEstimateOcrAdapter(
            DocumentStoragePort storagePort,
            EstimateOcrProperties properties,
            ObjectMapper objectMapper) {
        this.storagePort = storagePort;
        this.properties = properties;
        this.vendor = select(properties.provider());
        this.parser = new OcrExtractionParser(objectMapper, properties.minConfidence());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory(properties))
                .build();
        if (!properties.hasApiKey()) {
            log.warn("판독 어댑터가 키 없이 구성됐다. 실제 호출은 인증 오류로 실패한다. provider={}",
                    properties.provider());
        }
        log.info("견적서 판독 어댑터: {} (model={}, PDF 지원={})",
                vendor.name(), properties.model(), vendor.supportsPdf());
    }

    @Override
    public OcrExtraction extract(OcrDocument document) {
        requireSupportedFormat(document.fileType());

        byte[] content = storagePort.read(document.storageKey());
        String mediaType = mediaTypeOf(content, document.fileType());
        String base64 = Base64.getEncoder().encodeToString(content);

        long startedAt = System.currentTimeMillis();
        String text = callWithRetry(mediaType, base64);
        OcrExtraction extraction = parser.parse(text);

        log.info("견적서 판독 완료: provider={}, 항목={}건, {}ms",
                vendor.name(), extraction.items().size(), System.currentTimeMillis() - startedAt);
        return extraction;
    }

    /**
     * 고른 벤더가 이 형식을 받을 수 있는지 먼저 본다.
     *
     * <p><b>조용히 실패하거나 빈 결과를 내지 않는다.</b> OpenAI 를 고른 채 PDF 가 들어오면
     * 여기서 명확히 거절해 사용자가 "PDF 는 안 된다" 는 사실을 알 수 있게 한다. 빈 항목으로
     * 돌려보내면 "견적서에서 항목을 찾지 못했다" 로 둔갑해 원인을 영영 모르게 된다.
     */
    private void requireSupportedFormat(EstimateFileType fileType) {
        if (fileType == EstimateFileType.PDF && !vendor.supportsPdf()) {
            throw new UnsupportedDocumentFormatException("PDF는 현재 공급자에서 지원되지 않습니다.");
        }
    }

    /**
     * 판독은 멱등하므로 재시도해도 안전하다 — 같은 문서를 다시 읽을 뿐 부수 효과가 없다.
     * 다만 <b>호출마다 크레딧을 쓴다.</b> 그래서 상한이 낮고, 다시 불러도 똑같이 실패할 오류는
     * 한 번에 포기한다.
     *
     * <p><b>본문을 로그에 담지 않는다.</b> 남기는 것은 시도 횟수와 상태 코드뿐이고,
     * 응답 본문에는 견적서 내용이 그대로 들어 있다.
     */
    private String callWithRetry(String mediaType, String base64) {
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            try {
                return callOnce(mediaType, base64);
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if (!retryable(status)) {
                    log.error("판독 호출이 재시도할 수 없는 오류로 실패했다. status={}", status);
                    throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, failureMessageFor(status));
                }
                log.warn("판독 호출 실패 ({}/{}): status={}", attempt, properties.maxAttempts(), status);
            } catch (RuntimeException e) {
                log.warn("판독 호출 실패 ({}/{}): {}",
                        attempt, properties.maxAttempts(), e.getClass().getSimpleName());
            }
        }
        throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "견적서 판독에 실패했습니다.");
    }

    /**
     * 재시도할 오류인지 HTTP 상태 코드로만 가른다.
     *
     * <p><b>GMS 고유 오류 본문을 파싱하지 않는다.</b> 크레딧 소진·키 만료를 어떤 본문으로
     * 알려 주는지 문서에 없고 실측하지도 못했다. 확인되지 않은 형식에 맞춰 분기를 지어 넣으면
     * 틀렸을 때 조용히 잘못 분류된다. 상태 코드는 HTTP 표준이라 추측이 아니다.
     *
     * <ul>
     *   <li>401·403 — 키가 잘못됐거나 만료됐다. 다시 불러도 같다</li>
     *   <li>402 — 결제·크레딧 문제. 재시도가 남은 크레딧을 태운다</li>
     *   <li>그 밖의 4xx — 요청이 잘못됐다. 같은 요청을 다시 보낼 이유가 없다</li>
     *   <li>408·429 — 일시적일 수 있다. 5xx 도 마찬가지다</li>
     * </ul>
     */
    private static boolean retryable(int status) {
        if (status == 408 || status == 429) return true;
        return status >= 500;
    }

    /**
     * 실패 문구. <b>키·잔액 숫자를 넣지 않는다</b> — 이 메시지는 사용자 화면까지 갈 수 있다.
     * 상태 코드도 넣지 않는다.
     */
    private static String failureMessageFor(int status) {
        if (status == 401 || status == 403 || status == 402) {
            return "견적서 판독 서비스를 사용할 수 없습니다. 관리자에게 문의해 주세요.";
        }
        return "견적서 판독에 실패했습니다.";
    }

    private String callOnce(String mediaType, String base64) {
        Map<String, Object> body = vendor.body(
                properties.model(), EstimateOcrPrompt.INSTRUCTION, mediaType, base64,
                properties.maxOutputTokens());

        JsonNode response = restClient.post()
                .uri(vendor.path(properties.model()))
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> vendor.headers(nullSafeKey()).forEach(headers::set))
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("판독 응답이 비어 있다");
        }
        String text = vendor.extractText(response);
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("판독 응답에서 본문을 찾지 못했다");
        }
        return text;
    }

    private String nullSafeKey() {
        return properties.hasApiKey() ? properties.apiKey() : "";
    }

    /**
     * 첨부의 MIME 타입을 정한다.
     *
     * <p>{@link EstimateFileType} 은 {@code IMAGE}·{@code PDF} 까지만 구분하고 JPEG 인지 PNG 인지는
     * 모른다. 벤더는 그 구분을 요구하므로 <b>매직 바이트로 판별한다</b> — 확장자나 신고
     * Content-Type 을 믿지 않는 것은 {@code EstimateFileValidator} 가 이미 택한 방식이다.
     */
    private static String mediaTypeOf(byte[] content, EstimateFileType fileType) {
        if (fileType == EstimateFileType.PDF) return "application/pdf";
        if (content.length >= 8
                && (content[0] & 0xFF) == 0x89 && content[1] == 'P' && content[2] == 'N' && content[3] == 'G') {
            return "image/png";
        }
        return "image/jpeg";
    }

    private static LlmVendor select(String provider) {
        String wanted = provider == null ? "" : provider.strip().toLowerCase(Locale.ROOT);
        List<LlmVendor> vendors = LlmVendors.all();
        return vendors.stream()
                .filter(candidate -> candidate.name().equals(wanted))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "app.estimate-ocr.provider 는 %s 중 하나여야 한다: %s".formatted(
                                vendors.stream().map(LlmVendor::name).toList(), provider)));
    }

    private static ClientHttpRequestFactory requestFactory(EstimateOcrProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.timeout());
        factory.setReadTimeout(properties.timeout());
        return factory;
    }
}
