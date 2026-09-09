package com.ssafy.a307.member.controller;

import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.config.SecurityConfig;
import com.ssafy.a307.member.dto.MemberProfileResponse;
import com.ssafy.a307.member.dto.NicknameUpdateRequest;
import com.ssafy.a307.member.dto.ProfileImageCompleteRequest;
import com.ssafy.a307.member.dto.ProfileImageUploadUrlRequest;
import com.ssafy.a307.member.dto.ProfileImageUploadUrlResponse;
import com.ssafy.a307.member.service.MemberProfileService;
import com.ssafy.a307.member.service.MemberService;
import com.ssafy.a307.member.service.ProfileImageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CompositeLogoutHandler;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 마이페이지 — 내 정보 조회·닉네임 수정·탈퇴·프로필 이미지.
 * <p>
 * 모든 경로가 {@code /me} 로 시작하고 {@code /{memberId}} 형태가 없다.
 * 경로에서 회원을 받으면 남의 정보를 요구하는 요청이 성립해 소유자 검사를 따로 해야 하는데,
 * 세션에서만 꺼내면 그 문제가 아예 생기지 않는다.
 *
 * <p>인가는 {@code SecurityConfig} 의 {@code anyRequest().hasAnyRole("USER","ADMIN")} 이
 * 처리한다 — 가입 대기 세션({@code ROLE_SIGNUP_PENDING})은 여기 도달하지 못한다.
 */
@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberProfileService memberProfileService;
    private final MemberService memberService;
    private final ProfileImageService profileImageService;

    /**
     * 탈퇴 뒤 세션을 끊는 처리. {@code LogoutFilter} 가 {@code /api/auth/logout} 에만 걸려 있어
     * 여기서는 같은 핸들러를 직접 부른다.
     * <p>
     * 세션을 남겨 두면 탈퇴한 계정의 세션으로 API 를 계속 부를 수 있다 — 회원 행은
     * 익명화됐으므로 대부분 401 로 끊기지만, 살아 있는 세션을 남길 이유가 없다.
     */
    private final LogoutHandler logoutHandler = new CompositeLogoutHandler(
            new SecurityContextLogoutHandler(),
            new CookieClearingLogoutHandler(SecurityConfig.SESSION_COOKIE));

    @GetMapping("/me")
    public ApiResponse<MemberProfileResponse> me(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.of(memberProfileService.profile(principal.getMemberId()));
    }

    /**
     * 닉네임 수정. 갱신된 프로필을 그대로 돌려준다.
     * <p>
     * {@code PUT} 이 아니라 {@code PATCH} 인 이유 — 프로필에는 이메일·프로필 이미지도 있는데
     * 이 요청은 닉네임 하나만 바꾼다. {@code PUT} 은 나머지를 지우라는 뜻이 된다.
     */
    @PatchMapping("/me")
    public ApiResponse<MemberProfileResponse> changeNickname(
            @Valid @RequestBody NicknameUpdateRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        return ApiResponse.of(
                memberProfileService.changeNickname(principal.getMemberId(), request.nickname()));
    }

    /**
     * 탈퇴. 회원 행은 남기고 개인식별정보만 지우는 소프트 삭제다 —
     * 견적·검증 데이터의 FK 가 끊기면 안 되기 때문이다({@code MemberWithdrawalTest} 참고).
     * <p>
     * <b>소셜 unlink 와 S3 파일 삭제는 아직 하지 않는다.</b> 명세상 트랜잭션 밖
     * best-effort 인데 두 인프라 모두 코드에 붙어 있지 않다. 별도 스토리로 남긴다.
     */
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@AuthenticationPrincipal UserPrincipal principal,
                         HttpServletRequest request,
                         HttpServletResponse response) {

        memberService.withdraw(principal.getMemberId(), Instant.now());

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        logoutHandler.logout(request, response, authentication);
    }

    /**
     * 프로필 이미지 업로드 URL 발급. 브라우저가 이 URL 로 staging 버킷에 직접 PUT 한 뒤
     * {@link #changeProfileImage} 로 완료를 알린다.
     * <p>
     * 서버가 파일 본문을 받지 않으므로 multipart 상한(10MB)과 무관하다.
     * 사고 이미지({@code POST /api/accidents/{id}/images/upload-urls})와 같은 흐름이다.
     */
    @PostMapping("/me/profile-image/upload-url")
    public ApiResponse<ProfileImageUploadUrlResponse> issueProfileImageUploadUrl(
            @Valid @RequestBody ProfileImageUploadUrlRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        return ApiResponse.of(profileImageService.issueUploadUrl(
                principal.getMemberId(), request.contentType(), request.size()));
    }

    /**
     * 업로드 완료 통보 — 등록과 변경이 같은 요청이다. 키가
     * {@code profile/{memberId}} 하나로 고정돼 다시 올리면 덮어써지기 때문이다.
     */
    @PutMapping("/me/profile-image")
    public ApiResponse<MemberProfileResponse> changeProfileImage(
            @Valid @RequestBody ProfileImageCompleteRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        return ApiResponse.of(memberProfileService.changeProfileImage(
                principal.getMemberId(), request.uploadKey()));
    }

    /**
     * 이미지 삭제. 204 가 아니라 갱신된 프로필을 돌려준다 — 프론트가 기본 이미지로
     * 다시 그리려면 어차피 프로필이 필요해서, 조회를 한 번 더 부르지 않게 한다.
     */
    @DeleteMapping("/me/profile-image")
    public ApiResponse<MemberProfileResponse> deleteProfileImage(
            @AuthenticationPrincipal UserPrincipal principal) {

        return ApiResponse.of(memberProfileService.deleteProfileImage(principal.getMemberId()));
    }
}
