package com.ssafy.a307.analysis.callback;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * AI 서버가 분석을 마치고 보내는 callback 본문 (S15P21A307-157).
 *
 * <p>정본은 {@code Docs/Api/AI 연동 계약 (백엔드 ↔ AI 서버).md} ⑥ 절(2차 수정본, 2026-09-12)과
 * {@code Docs/Api/AI 서버 오류·재시도 처리 명세.md} 다. <b>계약을 바꾸는 곳이 아니다</b> —
 * 그 문서가 바뀌면 여기를 따라 고친다.
 *
 * <h2>{@code AnalysisDocument} 와 무엇이 다른가</h2>
 *
 * <p>이름이 비슷해 헷갈리기 쉬운데 <b>다른 계약이다.</b>
 * {@link com.ssafy.a307.analysis.contract.AnalysisDocument} 는 파이프라인이 소유한
 * 표준화 계약({@code inference-standardized-1.1.0})이고 <b>이미지 한 장</b> 단위 snake_case 다.
 * 이쪽은 AI 서버가 소유한 연동 계약이고 <b>분석 작업 하나</b> 단위 camelCase 이며 견적 항목까지
 * 담는다. 둘을 한 타입으로 합치려 하면 어느 쪽 계약도 강제하지 못하게 된다.
 *
 * <h2>성공과 실패가 한 타입인 이유</h2>
 *
 * <p>AI 서버는 같은 {@code callbackUrl} 로 성공·실패를 모두 보낸다(오류·재시도 명세 "실패
 * callback"). 경로가 하나이므로 본문도 하나로 받고, {@link #error} 의 유무로 가른다.
 * 실패 본문에도 {@code modelVersion}·{@code pipelineVersionId} 가 실려 오므로 그 값은 보존한다.
 *
 * <h2>미지 키를 거부하지 않는다</h2>
 *
 * <p>{@code AnalysisDocumentReader} 와 달리 {@code FAIL_ON_UNKNOWN_PROPERTIES} 를 켜지 않는다.
 * 그쪽은 <b>파이프라인이 필드를 추가해도 아무도 모르는 것</b>을 막으려는 것이고, 이쪽은 아직
 * "1차 수정본 … 협의 후 확정" 상태의 계약이라 AI 가 필드를 먼저 더할 수 있다. 모르는 키
 * 하나 때문에 분석 결과를 통째로 버리면 사용자가 잃는 것이 더 크다. 대신 <b>우리가 쓰는
 * 필드는 Bean Validation 으로 강제한다.</b>
 *
 * @param requestId         멱등 키. 헤더 {@code X-Request-Id} 와 같은 값이어야 한다
 * @param jobId             경로변수와 같아야 한다. 다르면 400
 * @param modelVersion      AI 모델 버전 문자열
 * @param pipelineVersionId AI 가 쓴 버전 조합 식별자. FK 가 아니라 값 보존용이다
 * @param estimable         산정 가능 여부. false 면 {@code totals}·{@code items} 가 비고
 *                          {@code nonEstimableReason} 만 온다
 * @param confidenceGrade   {@code HIGH}·{@code MEDIUM}·{@code LOW}·{@code null}
 * @param error             있으면 실패 callback 이다. 이때 작업은 FAILED 로 간다
 */
public record AnalysisCallbackRequest(

        @NotBlank(message = "requestId 는 필수입니다.")
        @Size(max = 64, message = "requestId 는 64자를 넘을 수 없습니다.")
        String requestId,

        @NotNull(message = "jobId 는 필수입니다.")
        Long jobId,

        @Size(max = 50, message = "modelVersion 은 50자를 넘을 수 없습니다.")
        String modelVersion,

        Long pipelineVersionId,

        @NotNull(message = "estimable 은 필수입니다.")
        Boolean estimable,

        String nonEstimableReason,

        String confidenceGrade,

        @Valid CallbackTotals totals,

        Integer refCaseTotal,
        Integer refYearFrom,
        Integer refYearTo,

        @Valid List<CallbackItem> items,

        @Valid List<CallbackImageResult> imageResults,

        @Valid CallbackError error) {

    public AnalysisCallbackRequest {
        items = items == null ? List.of() : List.copyOf(items);
        imageResults = imageResults == null ? List.of() : List.copyOf(imageResults);
    }

    /** 실패 callback 인가. 오류·재시도 명세는 {@code error} 가 있으면 작업을 FAILED 로 하라고 한다. */
    public boolean failed() {
        return error != null;
    }

    /**
     * 산정된 견적인가.
     *
     * <p>{@code estimable} 이 true 라도 실패 callback 이면 견적을 만들지 않는다 — 실패 본문의
     * {@code estimable} 은 "산정을 시도할 수 있었나" 가 아니라 계약이 기본값 {@code false} 로
     * 채워 보내는 자리다. 오류가 있으면 그 값을 믿지 않는다.
     */
    public boolean estimated() {
        return !failed() && Boolean.TRUE.equals(estimable);
    }
}
