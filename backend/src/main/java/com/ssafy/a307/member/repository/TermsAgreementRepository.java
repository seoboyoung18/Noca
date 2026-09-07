package com.ssafy.a307.member.repository;

import com.ssafy.a307.member.entity.TermsAgreement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TermsAgreementRepository extends JpaRepository<TermsAgreement, Long> {

    List<TermsAgreement> findAllByMemberId(Long memberId);
}
