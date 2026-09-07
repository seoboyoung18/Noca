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
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * 약관 동의 이력. 가입 시점에 사용자가 <b>실제로 본 버전</b>을 남긴다.
 * <p>
 * 갱신하지 않고 계속 쌓는다. 약관이 개정되면 새 버전으로 행이 하나 더 생긴다.
 * {@code member} 와 같은 담당 영역이지만 연관관계를 걸지 않고 {@code member_id} 를 값으로만 갖는다 —
 * 동의 이력을 읽자고 회원을 함께 로딩할 이유가 없다.
 */
@Entity
@Table(name = "terms_agreement")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TermsAgreement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "agreement_id")
    private Long agreementId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "terms_type", nullable = false, length = 20)
    private TermsType termsType;

    @Column(name = "version", nullable = false, length = 20)
    private String version;

    @CreatedDate
    @Column(name = "agreed_at", nullable = false, updatable = false)
    private Instant agreedAt;

    private TermsAgreement(Long memberId, TermsType termsType, String version) {
        this.memberId = memberId;
        this.termsType = termsType;
        this.version = version;
    }

    public static TermsAgreement of(Long memberId, TermsType termsType, String version) {
        return new TermsAgreement(memberId, termsType, version);
    }
}
