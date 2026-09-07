package com.ssafy.a307.auth.principal;

import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.member.entity.Provider;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 세션에 담기는 로그인 주체. <b>두 가지 상태</b>를 표현한다.
 * <ul>
 *   <li>가입 완료 — {@code memberId} 가 있고 권한은 {@code ROLE_USER} 또는 {@code ROLE_ADMIN}</li>
 *   <li>가입 대기 — 소셜 인증은 끝났지만 약관 동의 전이라 {@code memberId} 가 없고
 *       권한은 {@code ROLE_SIGNUP_PENDING} 하나뿐이다</li>
 * </ul>
 *
 * <p><b>왜 필드를 이만큼만 담는가</b> — 이 객체는 세션에 직렬화돼 Redis 로 들어간다.
 * {@link Member} 엔티티를 통째로 담으면 스키마가 바뀔 때마다 이미 로그인한 사용자의 세션이
 * 역직렬화에 실패해 전원 로그아웃된다. 그래서 바뀔 일이 거의 없는 값만 들고,
 * 나머지는 그때그때 DB 에서 읽는다.
 *
 * <p>필드를 추가하거나 지우면 {@code serialVersionUID} 를 올려 기존 세션을 의도적으로 끊어야 한다.
 * 그냥 두면 역직렬화가 어중간하게 성공해 필드가 null 인 주체가 돌아다닌다.
 */
public class UserPrincipal implements OAuth2User, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 가입 대기 상태의 권한. 이 권한만 가진 주체는 보호 API 에 접근하지 못한다. */
    public static final String ROLE_SIGNUP_PENDING = "ROLE_SIGNUP_PENDING";

    /** 가입 완료 회원의 PK. 가입 대기 상태에서는 null 이다. */
    private final Long memberId;

    private final Provider provider;

    private final String providerUserId;

    /** 소셜에서 받아온 닉네임. 가입 화면 입력값을 미리 채워 주는 용도로만 쓴다. */
    private final String socialNickname;

    private final Set<GrantedAuthority> authorities;

    private UserPrincipal(Long memberId, Provider provider, String providerUserId,
                          String socialNickname, Set<GrantedAuthority> authorities) {
        this.memberId = memberId;
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.socialNickname = socialNickname;
        this.authorities = authorities;
    }

    public static UserPrincipal ofMember(Member member) {
        return new UserPrincipal(
                member.getMemberId(),
                member.getProvider(),
                member.getProviderUserId(),
                member.getNickname(),
                Set.of(new SimpleGrantedAuthority(member.getRole().authority())));
    }

    /** 소셜 인증은 끝났지만 아직 회원이 아닌 상태. */
    public static UserPrincipal ofPendingSignup(Provider provider, String providerUserId,
                                                String socialNickname) {
        return new UserPrincipal(
                null,
                provider,
                providerUserId,
                socialNickname,
                Set.of(new SimpleGrantedAuthority(ROLE_SIGNUP_PENDING)));
    }

    public boolean isSignupPending() {
        return memberId == null;
    }

    public Long getMemberId() {
        return memberId;
    }

    public Provider getProvider() {
        return provider;
    }

    public String getProviderUserId() {
        return providerUserId;
    }

    public String getSocialNickname() {
        return socialNickname;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    /** 소셜 원본 응답은 담지 않는다. 세션 용량만 차지하고, 쓰는 값은 이미 필드로 뽑아 뒀다. */
    @Override
    public Map<String, Object> getAttributes() {
        return Map.of();
    }

    /** {@link OAuth2User} 규약상 주체를 식별하는 문자열. 소셜 계정 기준으로 이 값이 유일하다. */
    @Override
    public String getName() {
        return provider.name() + ":" + providerUserId;
    }
}
