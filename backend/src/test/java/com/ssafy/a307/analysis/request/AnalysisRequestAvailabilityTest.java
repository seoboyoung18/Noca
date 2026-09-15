package com.ssafy.a307.analysis.request;

import com.ssafy.a307.accident.entity.Accident;
import com.ssafy.a307.accident.image.AccidentImageStoragePort;
import com.ssafy.a307.accident.repository.AccidentImageRepository;
import com.ssafy.a307.accident.repository.AccidentRepository;
import com.ssafy.a307.analysis.callback.InternalApiProperties;
import com.ssafy.a307.analysis.repository.AnalysisJobRepository;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * 보낼 수 없는 환경에서는 접수하지 않는다 (S15P21A307-156) — 503.
 *
 * <p>스프링 컨텍스트 없이 본다. 저장소 어댑터가 "없음" 인 상태를 컨텍스트로 만들려면 버킷 설정을
 * 바꾼 컨텍스트가 하나 더 떠야 하는데, 여기서 보려는 것은 판정 한 줄뿐이다.
 */
@DisplayName("분석 요청 접수 — 설정이 없으면 503")
class AnalysisRequestAvailabilityTest {

    private final AccidentRepository accidentRepository = mock(AccidentRepository.class);
    private final AccidentImageRepository imageRepository = mock(AccidentImageRepository.class);
    private final AnalysisJobRepository jobRepository = mock(AnalysisJobRepository.class);

    @Test
    @DisplayName("사진 저장소가 없으면 503 이고 작업을 만들지 않는다")
    void missingStorageIsUnavailable() {
        ownedAccident();
        AnalysisRequestService service = service(properties("http://ai.test", "http://backend.test"),
                "token", Optional.empty());

        assertError(service, ErrorCode.SERVICE_UNAVAILABLE);
        then(jobRepository).should(never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("AI 서버 주소가 비어 있으면 503 이다")
    void missingAiUrlIsUnavailable() {
        ownedAccident();
        AnalysisRequestService service = service(properties("", "http://backend.test"),
                "token", Optional.of(mock(AccidentImageStoragePort.class)));

        assertError(service, ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("콜백 주소가 비어 있으면 503 이다 — AI 가 결과를 돌려보낼 곳이 없다")
    void missingCallbackUrlIsUnavailable() {
        ownedAccident();
        AnalysisRequestService service = service(properties("http://ai.test", " "),
                "token", Optional.of(mock(AccidentImageStoragePort.class)));

        assertError(service, ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("공유 토큰이 비어 있으면 503 이다 — AI 가 요청을 401 로 거절한다")
    void missingTokenIsUnavailable() {
        ownedAccident();
        AnalysisRequestService service = service(properties("http://ai.test", "http://backend.test"),
                "", Optional.of(mock(AccidentImageStoragePort.class)));

        assertError(service, ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("설정이 없어도 남의 사고는 404 가 먼저다 — 설정 상태를 알려 주지 않는다")
    void notFoundComesFirst() {
        given(accidentRepository.findByAccidentIdAndMemberId(7L, 1L)).willReturn(Optional.empty());
        AnalysisRequestService service = service(properties("", ""), "", Optional.empty());

        assertError(service, ErrorCode.NOT_FOUND);
    }

    private void ownedAccident() {
        given(accidentRepository.findByAccidentIdAndMemberId(7L, 1L))
                .willReturn(Optional.of(mock(Accident.class)));
    }

    private AnalysisRequestService service(AnalysisRequestProperties properties, String token,
                                           Optional<AccidentImageStoragePort> storage) {
        return new AnalysisRequestService(accidentRepository, imageRepository, jobRepository,
                properties, new InternalApiProperties(token), storage);
    }

    private static AnalysisRequestProperties properties(String aiBaseUrl, String callbackBaseUrl) {
        return new AnalysisRequestProperties(false, aiBaseUrl, callbackBaseUrl, 5,
                Duration.ofMinutes(10), Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    private static void assertError(AnalysisRequestService service, ErrorCode code) {
        assertThatThrownBy(() -> service.request(1L, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(code);
    }
}
