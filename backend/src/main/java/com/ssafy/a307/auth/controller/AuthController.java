package com.ssafy.a307.auth.controller;

import com.ssafy.a307.auth.AuthSessionPolicy;
import com.ssafy.a307.auth.dto.MeResponse;
import com.ssafy.a307.auth.dto.SignupContextResponse;
import com.ssafy.a307.auth.dto.SignupRequest;
import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.member.entity.TermsType;
import com.ssafy.a307.member.service.MemberService;
import com.ssafy.a307.member.service.TermsPolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 가입과 내 정보 조회.
 * <p>
 * 로그인 진입({@code /oauth2/authorization/{registrationId}})과 로그아웃({@code /api/auth/logout})은
 * 시큐리티 필터가 처리하므로 여기에 없다.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final MemberService memberService;

    /**
     * 가입 직후 세션의 권한을 올리려면 컨텍스트를 직접 저장해야 한다.
     * Spring Security 6 부터는 필터 체인이 끝날 때 자동 저장하지 않는다.
     */
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    /**
     * 가입 화면이 쓸 값. 소셜 인증을 마친 가입 대기 세션에서만 응답한다.
     * <p>
     * {@code permitAll} 경로라 세션 없이도 들어올 수 있어 여기서 직접 상태를 확인한다.
     */
    @GetMapping("/signup")
    public ApiResponse<SignupContextResponse> signupContext(
            @AuthenticationPrincipal UserPrincipal principal) {

        UserPrincipal pending = requirePendingSignup(principal);
        Set<String> requiredTerms = TermsPolicy.REQUIRED.stream()
                .map(Enum::name)
                .collect(Collectors.toUnmodifiableSet());

        return ApiResponse.of(new SignupContextResponse(
                pending.getProvider(), pending.getSocialNickname(), requiredTerms));
    }

    /**
     * 약관 동의와 함께 회원을 만든다. 성공하면 같은 세션이 그대로 로그인 세션으로 승격된다 —
     * 다시 소셜 로그인을 태우지 않는다.
     */
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<MeResponse>> signup(
            @Valid @RequestBody SignupRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        UserPrincipal pending = requirePendingSignup(principal);

        Set<TermsType> agreedTerms = request.agreedTerms();
        Member member = memberService.signup(
                pending.getProvider(), pending.getProviderUserId(), request.nickname(), agreedTerms);

        elevateToMemberSession(member, httpRequest, httpResponse);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(MeResponse.from(member)));
    }

    /**
     * 내 정보. 인가 규칙이 {@code ROLE_USER} 이상을 요구하므로 여기 도달하면 회원이 확정돼 있다.
     * <p>
     * 그래도 조회가 비면 401 로 끊는다. 세션은 살아 있는데 회원 행이 사라진 상태
     * (다른 기기에서 탈퇴 등)라 그대로 진행하면 안 된다.
     */
    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal UserPrincipal principal) {
        Member member = memberService.findById(principal.getMemberId())
                .filter(Member::isActive)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.UNAUTHORIZED, "다시 로그인해 주세요."));

        return ApiResponse.of(MeResponse.from(member));
    }

    private UserPrincipal requirePendingSignup(UserPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "소셜 로그인이 필요합니다.");
        }
        if (!principal.isSignupPending()) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 가입이 완료된 계정입니다.");
        }
        return principal;
    }

    /**
     * 가입 대기 세션을 회원 세션으로 바꾼다. 권한이 {@code ROLE_SIGNUP_PENDING} 에서
     * {@code ROLE_USER} 로 올라가고, 수명도 10분에서 30분으로 늘어난다.
     */
    private void elevateToMemberSession(Member member, HttpServletRequest request,
                                        HttpServletResponse response) {
        UserPrincipal principal = UserPrincipal.ofMember(member);
        Authentication authentication = new OAuth2AuthenticationToken(
                principal, principal.getAuthorities(), member.getProvider().registrationId());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        HttpSession session = request.getSession();
        session.setMaxInactiveInterval(AuthSessionPolicy.seconds(AuthSessionPolicy.AUTHENTICATED));
    }
}
