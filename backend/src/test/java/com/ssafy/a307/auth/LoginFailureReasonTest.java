package com.ssafy.a307.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("로그인 실패 사유 분류")
class LoginFailureReasonTest {

    /** 사용자가 소셜 동의 화면에서 취소하면 제공자가 콜백에 error=access_denied 를 싣는다. */
    @Test
    @DisplayName("동의 거부는 access_denied 다")
    void classifiesAccessDenied() {
        assertThat(LoginFailureReason.from(oauth2Error("access_denied")))
                .isEqualTo(LoginFailureReason.ACCESS_DENIED);
    }

    @Test
    @DisplayName("탈퇴 회원 차단은 withdrawn 이다")
    void classifiesWithdrawnMember() {
        assertThat(LoginFailureReason.from(oauth2Error("withdrawn_member")))
                .isEqualTo(LoginFailureReason.WITHDRAWN);
    }

    @Test
    @DisplayName("응답 파싱 실패는 invalid_response 다")
    void classifiesInvalidUserInfo() {
        assertThat(LoginFailureReason.from(oauth2Error("invalid_user_info_response")))
                .isEqualTo(LoginFailureReason.INVALID_RESPONSE);
    }

    /**
     * 모르는 코드를 특정 사유로 단정하면 사용자에게 엉뚱한 안내가 나간다.
     * "잠시 후 다시" 쪽으로 떨어뜨리는 편이 안전하다.
     */
    @Test
    @DisplayName("모르는 OAuth2 에러 코드는 server_error 로 떨어진다")
    void unknownOAuth2CodeFallsBackToServerError() {
        assertThat(LoginFailureReason.from(oauth2Error("invalid_token_response")))
                .isEqualTo(LoginFailureReason.SERVER_ERROR);
    }

    @Test
    @DisplayName("OAuth2 예외가 아니어도 server_error 로 분류한다")
    void nonOAuth2ExceptionFallsBackToServerError() {
        assertThat(LoginFailureReason.from(new InsufficientAuthenticationException("no auth")))
                .isEqualTo(LoginFailureReason.SERVER_ERROR);
    }

    /** 분류가 SERVER_ERROR 로 뭉쳐도 로그에서는 실제 원인을 구분할 수 있어야 한다. */
    @Test
    @DisplayName("원본 코드는 분류와 별개로 보존된다")
    void keepsRawCodeForLogging() {
        assertThat(LoginFailureReason.rawCodeOf(oauth2Error("invalid_token_response")))
                .isEqualTo("invalid_token_response");
        assertThat(LoginFailureReason.rawCodeOf(new InsufficientAuthenticationException("x")))
                .isEqualTo("InsufficientAuthenticationException");
    }

    /** 코드값은 프론트와의 계약이다. 바뀌면 안내 문구 분기가 깨진다. */
    @Test
    @DisplayName("코드값이 프론트 계약대로다")
    void codesMatchContract() {
        assertThat(LoginFailureReason.ACCESS_DENIED.code()).isEqualTo("access_denied");
        assertThat(LoginFailureReason.WITHDRAWN.code()).isEqualTo("withdrawn");
        assertThat(LoginFailureReason.INVALID_RESPONSE.code()).isEqualTo("invalid_response");
        assertThat(LoginFailureReason.SERVER_ERROR.code()).isEqualTo("server_error");
    }

    private OAuth2AuthenticationException oauth2Error(String errorCode) {
        return new OAuth2AuthenticationException(new OAuth2Error(errorCode, "테스트", null));
    }
}
