package com.ssafy.a307.common.llm;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * GMS 키가 실제로 들어와 있을 때만 LLM 계층 빈을 만든다.
 *
 * <p><b>{@code @ConditionalOnProperty} 로는 안 된다.</b> {@code app.gms.api-key} 는
 * {@code ${GMS_KEY:}} 참조라 환경변수가 없으면 <b>빈 문자열</b>로 존재한다. 그 애너테이션은
 * 값이 {@code false} 가 아니기만 하면 통과하므로, 키가 없는 환경에서도 빈이 떠 버린다.
 * 그러면 첫 호출에서야 401 을 받는다.
 *
 * <p><b>기동을 실패시키지 않는 것이 요점이다.</b> 키가 없다고 컨텍스트를 못 뜨게 하면 차량·사고
 * API 까지 함께 죽는다. 대신 이 조건으로 빈을 만들지 않으면, 소비처는 이 저장소의 기존 관례대로
 * {@code Optional<Port>} 로 받아 <b>없으면 503</b> 을 주면 된다
 * ({@code EstimateFileValidationService.storage()}, {@code AccidentImageService} 가 같은 형태다).
 */
public class GmsKeyPresentCondition implements Condition {

    static final String API_KEY_PROPERTY = "app.gms.api-key";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String key = context.getEnvironment().getProperty(API_KEY_PROPERTY, "");
        return !key.isBlank();
    }
}
