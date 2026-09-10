package com.ssafy.a307.config;

import com.ssafy.a307.auth.handler.OAuth2FailureHandler;
import com.ssafy.a307.auth.handler.OAuth2SuccessHandler;
import com.ssafy.a307.auth.handler.RestAccessDeniedHandler;
import com.ssafy.a307.auth.service.CustomOAuth2UserService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * 소셜 로그인 + 서버 세션. 자체 토큰을 발급하지 않고 세션 ID 를 HttpOnly 쿠키로 주고받는다.
 * <p>
 * {@code sessionCreationPolicy} 를 지정하지 않는다. 기본값 {@code IF_REQUIRED} 여야 로그인 시
 * 세션이 만들어진다. {@code STATELESS} 로 두면 Redis 설정이 맞아도 로그인이 성립하지 않는다.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
// CorsProperties 를 여기서 등록한다. @ConfigurationPropertiesScan 은 전체 컨텍스트에서만 돌아
// @WebMvcTest + @Import(SecurityConfig.class) 슬라이스에서는 빈이 없어 컨텍스트가 죽는다.
// 이 설정을 가져다 쓰는 쪽이 필요한 프로퍼티까지 함께 얻도록 자립시킨다.
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfig {

    /** 로그아웃. {@code PUBLIC_PATHS} 에 없어도 LogoutFilter 가 인가 필터보다 앞이라 도달한다. */
    private static final String LOGOUT_PATH = "/api/auth/logout";

    /**
     * Spring Session 이 세션 ID 를 담는 쿠키 이름(기본값).
     * <p>
     * 탈퇴({@code DELETE /api/members/me})도 같은 쿠키를 지워야 해서 공개한다.
     * 쿠키 이름을 두 군데 적으면 한쪽만 바뀌었을 때 탈퇴 후 쿠키가 남는다.
     */
    public static final String SESSION_COOKIE = "SESSION";

    /**
     * 인증 없이 열어 두는 경로. 이보다 넓히면 소유자 검사를 우회할 길이 생긴다.
     * <ul>
     *   <li>{@code /oauth2/authorization/**} — 소셜 로그인 진입</li>
     *   <li>{@code /login/oauth2/code/**} — 소셜 콜백</li>
     *   <li>{@code /api/auth/signup} — 가입(GET 은 화면용 정보, POST 는 가입). 가입 대기 세션에서만
     *       실제로 동작하며, 그 검사는 컨트롤러가 한다</li>
     *   <li>가이드 2종 — 로그인 전에도 보여 주는 정적 문안</li>
     * </ul>
     * {@code /error} 를 빼면 에러 디스패치가 401 로 뒤집혀 원래 상태 코드를 잃는다.
     */
    private static final String[] PUBLIC_PATHS = {
            "/oauth2/authorization/**",
            "/login/oauth2/code/**",
            "/api/auth/signup",
            "/api/guides/checklist",
            "/api/guides/shooting",
            "/actuator/health",
            "/error"
    };

    /**
     * 스웨거 UI 와 OpenAPI 문서. 로그인 전에도 열려야 문서를 볼 수 있다.
     * <p>
     * <b>배포 전에 닫아야 한다.</b> 엔드포인트 목록과 요청 스키마가 그대로 노출된다.
     * 프로파일 분리가 아직 없어 지금은 이 배열에 두고, 운영 프로파일이 생기면
     * {@code springdoc.api-docs.enabled=false} 로 끄거나 이 경로를 인가 대상으로 돌린다.
     */
    private static final String[] API_DOC_PATHS = {
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/v3/api-docs.yaml"
    };

    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;
    private final OAuth2FailureHandler oAuth2FailureHandler;
    private final RestAccessDeniedHandler restAccessDeniedHandler;
    private final CorsProperties corsProperties;

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/internal/**", "/inference/**").denyAll()
                .requestMatchers(PUBLIC_PATHS).permitAll()
                .requestMatchers(API_DOC_PATHS).permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                // authenticated() 를 쓰면 안 된다. 가입 대기 세션(ROLE_SIGNUP_PENDING)도
                // "인증됨"이라 그대로 통과하고, 회원이 없는 상태로 보호 API 에 들어온다.
                .anyRequest().hasAnyRole("USER", "ADMIN")
            )
            .oauth2Login(oauth -> oauth
                .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
                .successHandler(oAuth2SuccessHandler)
                .failureHandler(oAuth2FailureHandler)
            )
            .logout(logout -> logout
                // logoutUrl() 이 아니라 매처를 직접 준다. csrf 를 끈 상태에서 logoutUrl() 만 쓰면
                // LogoutFilter 가 GET·PUT·DELETE 까지 받아, <img src=".../api/auth/logout"> 한 줄이나
                // 브라우저 프리페치만으로 남의 세션이 끊긴다. 명세대로 POST 만 받는다.
                .logoutRequestMatcher(PathPatternRequestMatcher.withDefaults()
                    .matcher(HttpMethod.POST, LOGOUT_PATH))
                // 세션 무효화. Spring Session 이 감싼 세션이라 invalidate() 가 Redis 키까지 지운다
                // (indexed 저장소여서 principal 인덱스 항목도 함께 정리된다).
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                // Spring Session 도 세션이 죽으면 만료 쿠키를 내려보내지만, 그건 세션 저장소가
                // 붙어 있을 때만이다. 응답에 만료 Set-Cookie 가 항상 실리도록 여기서도 지운다.
                .deleteCookies(SESSION_COOKIE)
                // 본문 없는 204. 인가 필터보다 앞이라 세션이 없어도 여기까지 오며,
                // 그때도 204 다 — 로그아웃은 멱등이어야 프론트가 버튼을 두 번 눌러도 안전하다.
                .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
            )
            .exceptionHandling(ex -> ex
                // 401 은 기존 동작(본문 없는 sendError)을 유지한다. 공통 에러 포맷으로 바꾸면
                // 그 형태를 단언하는 ChecklistPublicAccessTest 를 함께 고쳐야 하는데,
                // 그 파일은 다른 담당자 영역이라 협의 후로 미룬다.
                .authenticationEntryPoint((req, res, e) ->
                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED))
                // 403 은 새로 생긴 응답이라 깨뜨릴 기존 계약이 없다. 가입 대기(SIGNUP_REQUIRED)와
                // 권한 부족(FORBIDDEN)을 구분해야 프론트가 갈 곳을 정할 수 있어 여기서 포맷을 맞춘다.
                .accessDeniedHandler(restAccessDeniedHandler)
            );
        return http.build();
    }

    /**
     * 허용 오리진은 {@link CorsProperties} 에서 온다. 여기에 값을 박으면 배포하는 순간
     * 전 화면이 막힌다 — FE 오리진이 바뀌는데 서버는 로컬 주소만 허용하기 때문이다.
     * <p>
     * {@code setAllowCredentials(true)} 는 유지한다. 세션 ID 를 쿠키로 주고받으므로 이걸 끄면
     * 브라우저가 쿠키를 안 싣고, 모든 보호 API 가 401 이 된다. 그래서 {@code "*"} 를 쓸 수 없고,
     * {@code CorsProperties} 가 기동 시점에 그것을 막는다.
     * <p>
     * {@code allowedHeaders} 는 {@code "*"} 그대로다. 자격증명과 함께 쓸 수 없는 것은
     * <b>오리진</b>의 {@code "*"} 이고, 헤더 쪽은 Spring 이 요청 헤더를 그대로 되비춰 준다.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(corsProperties.allowedOrigins());
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("*"));
        c.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource s = new UrlBasedCorsConfigurationSource();
        s.registerCorsConfiguration("/**", c);
        return s;
    }
}
