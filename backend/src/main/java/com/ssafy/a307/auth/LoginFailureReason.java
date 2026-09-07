package com.ssafy.a307.auth;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

/**
 * 로그인 실패 사유. 프론트가 안내 문구를 고르는 근거이고, 실패 로그의 분류 키이기도 하다.
 * <p>
 * <b>분류만 담고 상세는 담지 않는다.</b> 이 값은 리다이렉트 URL 의 쿼리로 나가 브라우저 이력에
 * 남는다. 어느 계정이 어떤 이유로 막혔는지가 거기 남으면 안 된다. 상세는 서버 로그에만 남긴다.
 */
public enum LoginFailureReason {

    /** 사용자가 소셜 동의 화면에서 취소하거나 거부했다. 재시도하면 된다. */
    ACCESS_DENIED("access_denied"),

    /** 탈퇴한 회원의 로그인 시도. 익명화가 정상 동작하면 여기 오지 않는다. */
    WITHDRAWN("withdrawn"),

    /** 소셜 응답이 우리가 기대한 모양이 아니다. 회원번호가 없는 경우 등. */
    INVALID_RESPONSE("invalid_response"),

    /** 그 외. 토큰 교환 실패, 네트워크 오류 등 사용자가 손쓸 수 없는 것들. */
    SERVER_ERROR("server_error");

    private final String code;

    LoginFailureReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /**
     * Spring Security 가 던진 예외를 분류로 바꾼다.
     * <p>
     * 소셜 제공자가 콜백에 {@code error=access_denied} 를 실어 보내면 Spring 이 그대로
     * {@link OAuth2AuthenticationException} 의 에러 코드로 옮겨 준다. 우리가 직접 던지는
     * {@code withdrawn_member} · {@code invalid_user_info_response} 도 같은 자리에 들어온다.
     * <p>
     * 모르는 코드는 {@link #SERVER_ERROR} 로 떨어뜨린다. 새 실패 유형이 생겼을 때
     * 사용자에게 엉뚱한 안내를 하느니 "잠시 후 다시" 쪽이 안전하다.
     */
    public static LoginFailureReason from(AuthenticationException exception) {
        if (!(exception instanceof OAuth2AuthenticationException oauth2Exception)) {
            return SERVER_ERROR;
        }
        return switch (oauth2Exception.getError().getErrorCode()) {
            case "access_denied" -> ACCESS_DENIED;
            case "withdrawn_member" -> WITHDRAWN;
            case "invalid_user_info_response" -> INVALID_RESPONSE;
            default -> SERVER_ERROR;
        };
    }

    /** 로그에 남길 원본 코드. 분류가 SERVER_ERROR 로 뭉쳐도 실제 원인을 추적할 수 있게 한다. */
    public static String rawCodeOf(AuthenticationException exception) {
        return exception instanceof OAuth2AuthenticationException oauth2Exception
                ? oauth2Exception.getError().getErrorCode()
                : exception.getClass().getSimpleName();
    }
}
