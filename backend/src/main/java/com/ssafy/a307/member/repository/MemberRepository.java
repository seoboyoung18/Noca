package com.ssafy.a307.member.repository;

import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.member.entity.Provider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    /**
     * 소셜 로그인의 유일한 진입 조회. {@code uk_member_provider} 가 그대로 인덱스로 쓰인다.
     * <p>
     * 탈퇴 회원은 {@code provider_user_id} 가 이미 익명화돼 있어 여기 걸리지 않는다.
     * 즉 이 메서드가 비어 있으면 "신규 가입" 하나만 뜻한다.
     */
    Optional<Member> findByProviderAndProviderUserId(Provider provider, String providerUserId);
}
