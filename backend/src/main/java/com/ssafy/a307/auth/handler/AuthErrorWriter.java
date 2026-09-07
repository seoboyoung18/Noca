package com.ssafy.a307.auth.handler;

import com.ssafy.a307.common.response.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 시큐리티 필터 단계에서 공통 에러 포맷을 직접 써 준다.
 * <p>
 * 401·403 은 {@code GlobalExceptionHandler} 를 <b>거치지 않는다.</b> 컨트롤러에 도달하기 전
 * 필터에서 끝나기 때문이다. 그래서 여기서 같은 모양을 손으로 맞춘다 —
 * 이걸 안 하면 인증 실패만 본문 없는 빈 응답으로 나가 프론트가 분기할 근거를 잃는다.
 *
 * <p>에러 코드를 {@code ErrorCode} enum 이 아니라 문자열로 넘긴다. 그 enum 은 다른 담당자의
 * 파일이라 지금 건드리지 않기 위해서다. 코드값 자체는 enum 이름과 같게 유지한다.
 */
@Component
@RequiredArgsConstructor
public class AuthErrorWriter {

    private final ObjectMapper objectMapper;

    public void write(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(),
                new ErrorResponse(new ErrorResponse.Error(code, message)));
    }
}
