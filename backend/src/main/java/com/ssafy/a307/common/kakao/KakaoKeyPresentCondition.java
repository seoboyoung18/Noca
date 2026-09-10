package com.ssafy.a307.common.kakao;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 카카오 REST API 키가 실제로 들어와 있을 때만 이 계층 빈을 만든다.
 *
 * <p><b>{@code @ConditionalOnProperty} 로는 안 된다.</b>
 * {@code app.kakao.local.rest-api-key} 는 {@code ${KAKAO_REST_API_KEY:}} 참조라 환경변수가
 * 없으면 <b>빈 문자열로 존재한다.</b> 그 애너테이션은 값이 {@code false} 가 아니기만 하면
 * 통과하므로 키가 없는 환경에서도 빈이 떠 버리고, 첫 호출에서야 {@code -401} 을 받는다.
 * {@link com.ssafy.a307.common.llm.GmsKeyPresentCondition} 이 같은 함정을 같은 방식으로 피한다.
 *
 * <p><b>기동을 실패시키지 않는 것이 요점이다.</b> 지도 키가 없다고 컨텍스트를 못 뜨게 하면
 * 차량·사고·견적 API 까지 함께 죽는다. 대신 이 조건으로 빈을 만들지 않으면, 소비처는 이
 * 저장소의 기존 관례대로 {@code Optional<KakaoLocalPort>} 로 받아 <b>없으면 503</b> 을 주면 된다.
 */
public class KakaoKeyPresentCondition implements Condition {

    static final String REST_API_KEY_PROPERTY = "app.kakao.local.rest-api-key";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return isKeyPresent(context);
    }

    static boolean isKeyPresent(ConditionContext context) {
        String key = context.getEnvironment().getProperty(REST_API_KEY_PROPERTY, "");
        return !key.isBlank();
    }

    /**
     * 키가 <b>없을 때만</b> 참. 기동 로그를 한 줄 남기는 빈에 쓴다.
     *
     * <p>왜 필요한가 — 키가 없으면 이 계층 빈이 아예 뜨지 않아 <b>로그도 아무것도 남지 않는다.</b>
     * 그러면 운영자는 위치 API 가 503 을 낼 때 "코드 버그인가 설정 누락인가" 를 구분할 단서가
     * 없다. 기동 시 한 줄이 그 질문에 미리 답한다.
     */
    public static class Absent implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !isKeyPresent(context);
        }
    }
}
