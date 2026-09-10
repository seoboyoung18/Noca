package com.ssafy.a307.common.kakao;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * 카카오 REST API 키가 없을 때 기동 로그를 <b>한 줄</b> 남긴다.
 *
 * <p>키가 없으면 {@link KakaoLocalConfig} 가 통째로 뜨지 않으므로 아무 로그도 남지 않는다.
 * 그 상태에서 위치 API 는 503 을 내는데, 운영자에게는 <b>"코드가 깨졌는가 설정이 빠졌는가"</b>
 * 를 구분할 단서가 없다. 기동 시 한 줄이 그 질문에 미리 답한다.
 *
 * <p>이 클래스는 빈을 만들지 않는다 — 로그만 남기고 사라진다.
 */
@Slf4j
@Configuration
@Conditional(KakaoKeyPresentCondition.Absent.class)
public class KakaoLocalDisabledConfig {

    @PostConstruct
    void warnDisabled() {
        log.info("카카오 로컬 연동 비활성 (키 미설정). "
                + "주소·좌표·장소 검색 API 는 503 을 반환한다. "
                + "활성화하려면 KAKAO_REST_API_KEY 환경변수를 설정한다.");
    }
}
