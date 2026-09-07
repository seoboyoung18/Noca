package com.ssafy.a307.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 스웨거 UI: {@code /swagger-ui.html} · 문서 원본: {@code /v3/api-docs}
 * <p>
 * <b>인증이 필요한 API 를 스웨거에서 호출하려면 먼저 브라우저로 로그인해야 한다.</b>
 * 소셜 로그인은 리다이렉트 연쇄라 스웨거 UI 가 따라갈 수 없다. 순서는 이렇다.
 * <ol>
 *   <li>주소창에 {@code /oauth2/authorization/kakao} 를 직접 입력해 카카오 로그인</li>
 *   <li>같은 브라우저로 스웨거 UI 를 연다 — 같은 오리진이라 SESSION 쿠키가 자동으로 붙는다</li>
 * </ol>
 * 인증 방식이 세션 쿠키라 스웨거의 Authorize 버튼(토큰 입력)은 쓰지 않는다.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI a307OpenApi() {
        return new OpenAPI().info(new Info()
                .title("A307 바른견적 API")
                .version("v1")
                .description("""
                        인증은 소셜 로그인 + 서버 세션이다. 세션 ID 는 HttpOnly 쿠키(SESSION)로 오간다.

                        응답 규약
                        - 성공 { "data": { ... } }
                        - 실패 { "error": { "code": "...", "message": "..." } }

                        인증 상태별 응답
                        - 미로그인 401
                        - 소셜 인증은 끝났지만 가입 전 403 SIGNUP_REQUIRED (약관 동의 화면으로)
                        """));
    }
}
