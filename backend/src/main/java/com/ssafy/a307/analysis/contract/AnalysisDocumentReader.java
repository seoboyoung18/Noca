package com.ssafy.a307.analysis.contract;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 표준화 계약 문서를 읽어 {@link AnalysisDocument} 로 바꾸고 계약을 강제한다.
 *
 * <h2>왜 JSON Schema 검증기를 쓰지 않았나</h2>
 *
 * <p>계약 정본은 JSON Schema({@code common_schema.json}) 지만 그것을 그대로 돌리려면 새 의존성이
 * 필요하다. 파이프라인은 파이썬 {@code jsonschema} 를 쓰고 백엔드에는 그런 라이브러리가 없다.
 * <b>새 의존성을 추가하지 않는다</b>는 이 저장소의 관례를 지키면서 계약을 강제하려고,
 * Jackson 역직렬화(구조·타입·미지 키)와 Bean Validation(값 범위·열거·상수)으로 나눠 걸었다.
 *
 * <h2>{@code additionalProperties: false} 를 어떻게 지키나</h2>
 *
 * <p>전역 {@code ObjectMapper} 설정에 기대지 않고 <b>이 컴포넌트가 쓰는 매퍼에
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} 를 직접 켠다.</b> Spring Boot 는 기본으로 그 기능을 꺼 두기
 * 때문에, 전역 설정에 맡기면 계약에 없는 키가 조용히 무시되고 <b>파이프라인이 필드를 추가해도
 * 아무도 모른다.</b> 매퍼를 주입받지 않고 직접 만드는 이유도 같다 — 다른 곳의 설정 변경이
 * 이 계약 검증을 약화시키지 못하게 한다.
 */
@Component
public class AnalysisDocumentReader {

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public AnalysisDocumentReader(Validator validator) {
        this.validator = validator;
        this.objectMapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    /** JSON 문자열을 읽는다. 계약 위반이면 {@link ErrorCode#INVALID_REQUEST}. */
    public AnalysisDocument read(String json) {
        if (json == null || json.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "분석 결과 문서가 비어 있습니다.");
        }
        AnalysisDocument document;
        try {
            document = objectMapper.readValue(json, AnalysisDocument.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "분석 결과 문서가 계약과 맞지 않습니다: " + rootMessage(e));
        }
        return validated(document);
    }

    /** 이미 객체로 들어온 문서를 검증한다. */
    public AnalysisDocument validated(AnalysisDocument document) {
        if (document == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "분석 결과 문서가 비어 있습니다.");
        }
        Set<ConstraintViolation<AnalysisDocument>> violations = validator.validate(document);
        if (!violations.isEmpty()) {
            String detail = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining(", "));
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "분석 결과 문서가 계약과 맞지 않습니다: " + detail);
        }
        return document;
    }

    /** 트리로 먼저 읽어야 하는 호출자를 위한 편의 메서드. */
    public AnalysisDocument read(JsonNode node) {
        if (node == null || node.isNull()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "분석 결과 문서가 비어 있습니다.");
        }
        return read(node.toString());
    }

    /**
     * 예외 메시지에서 첫 줄만 남긴다. Jackson 은 입력 일부를 메시지에 실을 수 있는데,
     * 견적서·사고 사진 맥락에서는 <b>원문이 로그와 응답에 섞여 나가면 안 된다.</b>
     */
    private String rootMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        int lineBreak = message.indexOf('\n');
        String firstLine = lineBreak < 0 ? message : message.substring(0, lineBreak);
        return firstLine.length() > 200 ? firstLine.substring(0, 200) : firstLine;
    }
}
