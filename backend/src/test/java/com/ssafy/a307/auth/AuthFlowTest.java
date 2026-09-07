package com.ssafy.a307.auth;

import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.member.entity.Provider;
import com.ssafy.a307.member.repository.MemberRepository;
import com.ssafy.a307.member.repository.TermsAgreementRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 약관 후 가입 흐름을 필터 체인을 켠 채로 검증한다.
 * <p>
 * {@code addFilters = false} 를 쓰지 않는다. 이 테스트가 보려는 것 대부분이 인가 규칙과
 * 예외 처리라, 필터를 빼면 검증 대상이 통째로 사라진다.
 *
 * <p>카카오 서버는 호출하지 않는다. 소셜 인증이 끝난 <b>다음</b> 상태를 세션에 직접 심어
 * 그 지점부터 검증한다. 소셜 응답 파싱은 {@code OAuth2UserInfoTest} 가 따로 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration")
@DisplayName("약관 후 가입 흐름")
class AuthFlowTest {

    private static final String KAKAO_ID = "3812345678";
    private static final String GOOGLE_SUB = "104729384756102938475";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private TermsAgreementRepository termsAgreementRepository;

    @Test
    @DisplayName("로그인하지 않으면 보호 API 는 401 이다")
    void anonymousGets401() throws Exception {
        mockMvc.perform(get("/api/vehicles/me"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 이 테스트가 {@code anyRequest().authenticated()} 를 쓰면 안 되는 이유를 고정한다.
     * 가입 대기 세션도 "인증됨"이라, {@code authenticated()} 였다면 여기서 200 이 나오고
     * 회원이 없는 상태로 보호 API 에 들어간다.
     */
    @Test
    @DisplayName("가입 대기 세션으로 보호 API 를 부르면 403 SIGNUP_REQUIRED 다")
    void pendingSignupGets403WithDedicatedCode() throws Exception {
        mockMvc.perform(get("/api/vehicles/me").session(pendingSession()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SIGNUP_REQUIRED"));
    }

    @Test
    @DisplayName("가입 화면은 소셜 닉네임과 필수 약관 목록을 받아 간다")
    void signupContextIsAvailableWhilePending() throws Exception {
        mockMvc.perform(get("/api/auth/signup").session(pendingSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("KAKAO"))
                .andExpect(jsonPath("$.data.socialNickname").value("카카오닉네임"))
                .andExpect(jsonPath("$.data.requiredTerms.length()").value(2));
    }

    @Test
    @DisplayName("세션 없이 가입 화면 정보를 요청하면 401 이다 — permitAll 이라 필터는 통과한다")
    void signupContextRequiresPendingSession() throws Exception {
        mockMvc.perform(get("/api/auth/signup"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("가입하면 회원과 약관 동의 이력이 함께 생긴다")
    void signupCreatesMemberAndTermsAgreements() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .session(pendingSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("보영")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.nickname").value("보영"))
                .andExpect(jsonPath("$.data.provider").value("KAKAO"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.email").doesNotExist());

        Long memberId = memberRepository.findByProviderAndProviderUserId(Provider.KAKAO, KAKAO_ID)
                .orElseThrow()
                .getMemberId();
        assertThat(termsAgreementRepository.findAllByMemberId(memberId)).hasSize(2);
    }

    /**
     * 가입 후 다시 소셜 로그인을 태우지 않는다. 같은 세션의 권한이
     * {@code ROLE_SIGNUP_PENDING} 에서 {@code ROLE_USER} 로 올라간다.
     */
    @Test
    @DisplayName("가입한 세션은 그대로 로그인 세션이 된다")
    void signupElevatesTheSameSession() throws Exception {
        MockHttpSession session = pendingSession();

        mockMvc.perform(post("/api/auth/signup")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("보영")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("보영"));
    }

    /**
     * 가입 흐름은 provider 를 가리지 않는다. 컨트롤러·핸들러·세션 승격이 모두
     * {@code UserPrincipal} 만 보고 동작하므로, 구글이 붙어도 분기가 생기지 않아야 한다.
     */
    @Test
    @DisplayName("구글도 같은 흐름으로 가입된다 — provider 별 분기가 없다")
    void googleFollowsTheSameSignupFlow() throws Exception {
        MockHttpSession session = pendingSession(Provider.GOOGLE, GOOGLE_SUB, "구글표시이름");

        mockMvc.perform(get("/api/auth/signup").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("GOOGLE"))
                .andExpect(jsonPath("$.data.socialNickname").value("구글표시이름"));

        mockMvc.perform(post("/api/auth/signup")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("보영")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.provider").value("GOOGLE"))
                .andExpect(jsonPath("$.data.email").doesNotExist());

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("GOOGLE"));

        assertThat(memberRepository.findByProviderAndProviderUserId(Provider.GOOGLE, GOOGLE_SUB))
                .isPresent();
    }

    @Test
    @DisplayName("구글 가입 대기 세션도 보호 API 에서 403 SIGNUP_REQUIRED 다")
    void googlePendingSignupIsAlsoBlocked() throws Exception {
        mockMvc.perform(get("/api/vehicles/me")
                        .session(pendingSession(Provider.GOOGLE, GOOGLE_SUB, "구글표시이름")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SIGNUP_REQUIRED"));
    }

    @Test
    @DisplayName("필수 약관이 빠지면 가입을 거절한다")
    void rejectsSignupMissingRequiredTerms() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .session(pendingSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"보영","agreedTerms":["SERVICE"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(memberRepository.findByProviderAndProviderUserId(Provider.KAKAO, KAKAO_ID))
                .isEmpty();
    }

    /** {@code member.nickname} 이 VARCHAR(12) 라, DB 까지 가기 전에 막아야 한다. */
    @Test
    @DisplayName("닉네임이 12자를 넘으면 거절한다")
    void rejectsTooLongNickname() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .session(pendingSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("가나다라마바사아자차카타파")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("이미 가입을 마친 세션으로 다시 가입하면 409 다")
    void rejectsDuplicateSignup() throws Exception {
        MockHttpSession session = pendingSession();
        mockMvc.perform(post("/api/auth/signup")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("보영")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/signup")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("보영2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    private String signupBody(String nickname) {
        return """
                {"nickname":"%s","agreedTerms":["SERVICE","PRIVACY"]}
                """.formatted(nickname);
    }

    /** 소셜 인증만 끝난 상태의 세션. {@code OAuth2SuccessHandler} 가 만들어 내는 것과 같은 모양이다. */
    private MockHttpSession pendingSession() {
        return pendingSession(Provider.KAKAO, KAKAO_ID, "카카오닉네임");
    }

    private MockHttpSession pendingSession(Provider provider, String providerUserId,
                                           String socialNickname) {
        UserPrincipal principal =
                UserPrincipal.ofPendingSignup(provider, providerUserId, socialNickname);
        Authentication authentication = new OAuth2AuthenticationToken(
                principal, principal.getAuthorities(), provider.registrationId());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }
}
