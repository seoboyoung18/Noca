package com.ssafy.a307.member.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * 소셜 로그인으로 가입한 회원.
 * <p>
 * {@code provider_user_id} 는 반드시 {@link String} 이다. 카카오는 회원번호를 숫자로 주지만
 * 구글은 {@code sub} 문자열을 주므로, 타입을 숫자로 잡으면 구글을 붙이는 순간 깨진다.
 * <p>
 * 시각 컬럼이 전부 {@code TIMESTAMPTZ} 라 {@link Instant} 로 매핑한다.
 */
@Entity
@Table(name = "member")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {

    /** 탈퇴 시 닉네임을 이 값으로 덮는다. {@code nickname} 이 VARCHAR(12) 라 4글자로 맞춰 둔다. */
    private static final String WITHDRAWN_NICKNAME = "탈퇴회원";

    /** 탈퇴 시 {@code provider_user_id} 앞에 붙이는 접두사. 카카오·구글 ID 형식과 겹치지 않는다. */
    private static final String WITHDRAWN_ID_PREFIX = "withdrawn:";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id")
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private Provider provider;

    @Column(name = "provider_user_id", nullable = false, length = 255)
    private String providerUserId;

    @Column(name = "nickname", nullable = false, length = 12)
    private String nickname;

    /** 현재 카카오 scope 가 {@code profile_nickname} 뿐이라 항상 null 로 들어온다. */
    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "profile_image_key", length = 500)
    private String profileImageKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private MemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MemberStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    private Member(Provider provider, String providerUserId, String nickname) {
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.nickname = nickname;
        this.role = MemberRole.USER;
        this.status = MemberStatus.ACTIVE;
    }

    public static Member register(Provider provider, String providerUserId, String nickname) {
        return new Member(provider, providerUserId, nickname);
    }

    public boolean isActive() {
        return status == MemberStatus.ACTIVE;
    }

    public void changeNickname(String nickname) {
        this.nickname = nickname;
    }

    /**
     * 탈퇴 — 개인식별정보를 <b>즉시</b> 익명화한다.
     * <p>
     * {@code uk_member_provider (provider, provider_user_id)} 가 전체 UNIQUE 라, 탈퇴 행이
     * 원래 값을 들고 있으면 같은 소셜 계정으로 영영 재가입할 수 없다. 그래서 유예기간을 두지 않고
     * 이 자리에서 슬롯을 비운다. 재로그인하면 조회에 걸리지 않으므로 자연스럽게 신규 가입으로 흐른다.
     * <p>
     * 행 자체는 지우지 않는다. {@code vehicle}·{@code accident}·{@code estimate_validation} 이
     * {@code ON DELETE RESTRICT} 로 참조하고 있어 물리 삭제가 불가능하고, 견적·검증 데이터를
     * 보존하기로 했기 때문이다. {@code member_id} 가 남으므로 FK 는 끊기지 않는다.
     * <p>
     * 되돌릴 수 없다. 호출 전에 사용자에게 경고해야 한다.
     */
    public void withdraw(Instant now) {
        if (status == MemberStatus.WITHDRAWN) {
            return;
        }
        this.status = MemberStatus.WITHDRAWN;
        this.withdrawnAt = now;
        this.providerUserId = WITHDRAWN_ID_PREFIX + memberId;
        this.nickname = WITHDRAWN_NICKNAME;
        this.email = null;
        this.profileImageKey = null;
    }
}
