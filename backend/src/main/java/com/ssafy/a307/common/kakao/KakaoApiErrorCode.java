package com.ssafy.a307.common.kakao;

import com.ssafy.a307.common.exception.ErrorCode;

import java.util.Arrays;
import java.util.Optional;

/**
 * 카카오가 응답 본문 {@code code} 로 알려주는 실패 사유와 이 프로젝트의 처리 방침.
 *
 * <h2>HTTP 상태 코드만으로는 판단할 수 없다</h2>
 * 카카오는 <b>대부분의 실패를 HTTP 400 에 담아 보내고 실제 원인은 본문 {@code code} 에 있다.</b>
 * 쿼터 초과({@code -10})도 400 이고 서비스 점검({@code -7})도 400 이다. 상태 코드만 보고
 * 재시도를 결정하면 <b>쿼터가 이미 바닥난 상황에서 재시도로 더 태우게 된다.</b>
 *
 * <h2>재시도는 세 경우뿐이다</h2>
 * {@code -1}(카카오 내부 처리 에러), {@code -603}(카카오 플랫폼 내부 타임아웃), 그리고
 * 연결·읽기 타임아웃. 파라미터 오류({@code -2})·쿼터 초과({@code -10})·앱키 오류({@code -401})는
 * 같은 요청을 다시 보내면 <b>똑같이 실패하면서 쿼터만 소모한다.</b>
 *
 * <h2>운영자용 로그가 필요한 경우를 구분한다</h2>
 * {@code -3}·{@code -5}·{@code -13}·{@code -401} 은 사용자가 무엇을 해도 해결되지 않는다.
 * 카카오 앱 관리 페이지에서 설정을 켜거나 키를 고쳐야 한다. 그런 사유는
 * {@link #needsOperatorAttention()} 으로 표시해 로그 레벨을 올린다 — 사용자에게는 똑같이
 * "일시적으로 사용할 수 없습니다" 가 나가지만 운영자는 원인을 알아야 한다.
 *
 * <p><b>카카오 로그인·메시지·채널 계열 코드({@code -101}, {@code -402}, {@code -502} 등)는
 * 여기 없다.</b> Local API 에서 발생하지 않는 코드를 매핑에 넣으면, 실제로 그 코드를 받았을 때
 * 잘못된 분류로 조용히 처리된다.
 */
public enum KakaoApiErrorCode {

    /** 카카오 서버 내부에서 처리 중 오류. 일시적일 수 있어 <b>재시도 대상</b>이다. */
    INTERNAL_PROCESSING(-1, ErrorCode.SERVICE_UNAVAILABLE, true, false),

    /**
     * 필수 인자 누락·타입 불일치·허용 범위 초과. <b>우리 요청이 잘못된 것이다.</b>
     * 재시도하면 같은 결과라 하지 않고, 어떤 파라미터였는지 로그로 남겨 추적한다.
     */
    INVALID_PARAMETER(-2, ErrorCode.INVALID_REQUEST, false, false),

    /** 앱 기능 활성화·호출 허용 설정 누락. 카카오 앱 관리 페이지 설정이 필요하다. */
    APP_DISABLED(-3, ErrorCode.SERVICE_UNAVAILABLE, false, true),

    /** 해당 API 요청 권한 없음. 앱에 로컬 API 사용 권한을 켜야 한다. */
    NO_PERMISSION(-5, ErrorCode.SERVICE_UNAVAILABLE, false, true),

    /** 카카오가 허용하지 않는 동작. 요청 자체가 잘못됐다. */
    NOT_ALLOWED(-6, ErrorCode.INVALID_REQUEST, false, false),

    /** 서비스 점검 또는 카카오 내부 문제. <b>재시도하지 않는다</b> — 점검은 곧 끝나지 않는다. */
    UNDER_MAINTENANCE(-7, ErrorCode.SERVICE_UNAVAILABLE, false, false),

    /** 올바르지 않은 헤더. <b>우리 코드 버그다</b> — 헤더 구성을 고쳐야 한다. */
    INVALID_HEADER(-8, ErrorCode.INTERNAL_ERROR, false, true),

    /** 종료된 API 호출. 우리가 없어진 엔드포인트를 부르고 있다. */
    DEPRECATED_API(-9, ErrorCode.INTERNAL_ERROR, false, true),

    /**
     * 허용 요청 회수(쿼터) 초과. <b>절대 재시도하지 않는다</b> —
     * 이미 바닥난 쿼터를 재시도로 더 태우면 복구가 늦어진다.
     */
    QUOTA_EXCEEDED(-10, ErrorCode.TOO_MANY_REQUESTS, false, true),

    /** 유료 API 의 일·월 한도 초과. 결제·한도 조정이 필요하다. */
    PAID_QUOTA_EXCEEDED(-11, ErrorCode.SERVICE_UNAVAILABLE, false, true),

    /** 앱이 장기 미이용 상태. 카카오 앱 관리 페이지에서 해제해야 한다. */
    APP_DORMANT(-13, ErrorCode.SERVICE_UNAVAILABLE, false, true),

    /**
     * 유효하지 않은 앱키 또는 등록 앱 정보 불일치.
     * <p>
     * <b>재시도 금지</b>이고 <b>키를 로그에 찍지 않는다.</b> 원인을 알려면 키 값이 필요할 것
     * 같지만, 로그에 남은 키는 되돌릴 수 없다. 운영자는 이 코드만 보고 키 설정을 확인하면 된다.
     */
    INVALID_APP_KEY(-401, ErrorCode.SERVICE_UNAVAILABLE, false, true),

    /** 카카오 플랫폼 내부 타임아웃. 일시적이라 <b>재시도 대상</b>이다. */
    PLATFORM_TIMEOUT(-603, ErrorCode.SERVICE_UNAVAILABLE, true, false),

    /** 서비스 점검중(HTTP 503). 재시도하지 않는다. */
    SERVICE_MAINTENANCE(-9798, ErrorCode.SERVICE_UNAVAILABLE, false, false);

    private final int code;
    private final ErrorCode errorCode;
    private final boolean retryable;
    private final boolean needsOperatorAttention;

    KakaoApiErrorCode(int code, ErrorCode errorCode,
                      boolean retryable, boolean needsOperatorAttention) {
        this.code = code;
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.needsOperatorAttention = needsOperatorAttention;
    }

    /**
     * 알 수 없는 코드는 {@link Optional#empty()} 다.
     *
     * <p>모르는 코드를 임의로 분류하지 않는다 — 그러면 나중에 카카오가 새 코드를 추가했을 때
     * 잘못된 처리가 조용히 굳는다. 호출부는 {@link #unmappedFallback()} 으로 보수적으로
     * 처리하고 원본 코드를 로그에 남긴다.
     */
    public static Optional<KakaoApiErrorCode> of(Integer code) {
        if (code == null) return Optional.empty();
        return Arrays.stream(values()).filter(value -> value.code == code).findFirst();
    }

    /** 매핑에 없는 코드의 처리. 재시도하지 않고 503 이다 — 모르는 것을 다시 보내지 않는다. */
    public static ErrorCode unmappedFallback() {
        return ErrorCode.SERVICE_UNAVAILABLE;
    }

    public int code() {
        return code;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 같은 요청을 다시 보내면 결과가 달라질 수 있는가. */
    public boolean isRetryable() {
        return retryable;
    }

    /** 사용자가 무엇을 해도 해결되지 않고 운영자 조치가 필요한가. */
    public boolean needsOperatorAttention() {
        return needsOperatorAttention;
    }
}
