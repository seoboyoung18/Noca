package com.ssafy.a307.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
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

    /**
     * 로그아웃을 문서에 직접 얹는다.
     * <p>
     * springdoc 은 {@code @RestController} 의 메서드만 훑는데, 로그아웃은 시큐리티의
     * {@code LogoutFilter} 가 처리해 컨트롤러 메서드가 없다. 그래서 자동으로는 절대 수집되지 않는다.
     * <p>
     * <b>문서에만 얹어도 "Try it out" 은 실제로 동작한다.</b> 요청이 디스패처에 닿기 전에
     * 필터가 가로채기 때문이다. 스웨거 UI 와 API 가 같은 오리진이라 SESSION 쿠키도 그대로 붙는다.
     * <p>
     * 태그를 {@code auth-controller} 로 맞춰 {@link com.ssafy.a307.auth.controller.AuthController}
     * 와 같은 그룹에 묶는다. 이름이 어긋나면 문서에 그룹이 하나 더 생긴다.
     */
    @Bean
    OpenApiCustomizer logoutOperation() {
        Operation logout = new Operation()
                .addTagsItem("auth-controller")
                .summary("로그아웃")
                .description("""
                        서버 세션을 무효화하고 SESSION 쿠키를 만료시킨다.

                        - 세션이 없어도 204 다. 멱등이라 프론트가 실패 분기를 만들지 않아도 된다
                        - POST 만 받는다. GET 으로는 로그아웃되지 않는다
                        - 로그아웃한 세션 ID 를 다시 제시해도 인증이 복구되지 않는다
                        """)
                .responses(new ApiResponses().addApiResponse("204",
                        new ApiResponse().description("로그아웃 완료. 본문 없음")));

        return openApi -> openApi.path("/api/auth/logout", new PathItem().post(logout));
    }
}
