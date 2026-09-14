package com.ssafy.a307.analysis.callback;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 서버 → 백엔드 분석 결과 수신 (S15P21A307-157).
 *
 * <p>경로는 {@code Docs/Api/AI 연동 계약 (백엔드 ↔ AI 서버).md} ⑥ 절이 정한
 * {@code POST /internal/analysis-jobs/{jobId}/result} 다. 공개 조회 API 인
 * {@code GET /api/analysis-jobs/{jobId}/result} 와 접두사({@code /internal} vs {@code /api})와
 * 메서드가 달라 충돌하지 않는다.
 *
 * <h2>왜 세션이 아니라 토큰인가</h2>
 *
 * <p>부르는 쪽이 사용자가 아니라 AI 서버다. 세션이 없고 만들 수도 없다. 대신
 * {@code X-Internal-Token} 공유 비밀과 <b>네트워크 차단</b>(EC2 사설 IP) 두 겹으로 막는다.
 * 토큰만으로 충분하다고 보지 않는다 — 토큰은 네트워크가 뚫렸을 때의 마지막 문이다.
 *
 * <h2>토큰이 틀리면 401 이 아니라 404 다</h2>
 *
 * <p>계약이 "실패 시 401 아니라 404(은닉)" 이라고 적었다. 401 은 <b>"이 경로가 있다"</b>를
 * 알려 주고, 그러면 남은 일은 토큰을 맞히는 것뿐이다. 404 는 경로의 존재 자체를 감춘다.
 * 같은 이유로 <b>토큰 검사를 작업 조회보다 먼저</b> 한다 — 순서가 바뀌면 응답 시간 차이로
 * 어느 {@code jobId} 가 실재하는지 알아낼 수 있다.
 *
 * <h2>왜 200 만 돌려주나</h2>
 *
 * <p>AI 서버는 callback 이 실패하면 1초·5초·20초 간격으로 최대 3회 재시도한다. 우리가 5xx 를
 * 주면 같은 결과가 네 번 온다. 저장까지 끝났으면 200 을 주어 재시도를 멈춘다. 본문이 계약을
 * 어겼을 때만 400 으로 끊는다 — 그것은 다시 보내도 똑같이 실패할 요청이다.
 */
@RestController
@RequestMapping("/internal/analysis-jobs")
@RequiredArgsConstructor
public class AnalysisCallbackController {

    /** 계약이 정한 헤더 이름. */
    public static final String TOKEN_HEADER = "X-Internal-Token";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final InternalApiProperties internalApi;
    private final AnalysisCallbackService callbackService;

    /**
     * 분석 결과 수신. 성공·실패 callback 이 같은 경로로 온다.
     *
     * @return 항상 200. 같은 {@code requestId} 가 다시 와도 200 이며 저장은 하지 않는다
     */
    @PostMapping("/{jobId}/result")
    @ResponseStatus(HttpStatus.OK)
    public AnalysisCallbackResponse receive(
            @PathVariable Long jobId,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestIdHeader,
            @Valid @RequestBody AnalysisCallbackRequest request) {

        if (!internalApi.matches(token)) {
            // 은닉. "토큰이 틀렸다" 고 알려 주지 않는다.
            throw new BusinessException(ErrorCode.NOT_FOUND, "존재하지 않는 경로입니다.");
        }
        return callbackService.receive(jobId, requestIdHeader, request);
    }

    /**
     * 처리 결과. AI 서버가 로그로 남길 수 있게 무엇을 했는지 알려 준다.
     *
     * <p>공통 래퍼({@code ApiResponse}) 를 쓰지 않는다. 그 래퍼는 프론트와의 계약이고, 이쪽은
     * AI 서버와의 계약이라 문서가 다르다. 한쪽 형식을 바꾸면 다른 쪽이 깨지는 결합을 만들지 않는다.
     *
     * @param duplicate 같은 {@code requestId} 를 이미 처리해 이번에는 저장하지 않았다
     */
    public record AnalysisCallbackResponse(Long jobId, String status, boolean duplicate) {
    }
}
