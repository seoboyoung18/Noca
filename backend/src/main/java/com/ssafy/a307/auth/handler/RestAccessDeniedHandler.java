package com.ssafy.a307.auth.handler;

import com.ssafy.a307.auth.principal.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 인증은 됐지만 권한이 모자란 요청. 두 갈래로 나뉜다.
 * <ul>
 *   <li><b>가입 대기</b> — 소셜 인증만 끝난 상태. 프론트는 약관·가입 화면으로 보내야 한다</li>
 *   <li><b>그 외</b> — 관리자 전용 API 에 일반 회원이 접근한 경우 등</li>
 * </ul>
 *
 * <p>둘 다 403 이지만 코드가 달라야 한다. 같은 코드로 내려보내면 프론트가 가입 화면으로 보낼지
 * 권한 없음을 띄울지 판단할 수 없다.
 */
@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final AuthErrorWriter errorWriter;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        if (isSignupPending()) {
            errorWriter.write(response, HttpStatus.FORBIDDEN,
                    "SIGNUP_REQUIRED", "약관 동의 후 가입을 완료해 주세요.");
            return;
        }
        errorWriter.write(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다.");
    }

    private boolean isSignupPending() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getPrincipal() instanceof UserPrincipal principal
                && principal.isSignupPending();
    }
}
