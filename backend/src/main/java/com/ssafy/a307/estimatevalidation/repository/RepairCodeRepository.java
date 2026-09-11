package com.ssafy.a307.estimatevalidation.repository;

import com.ssafy.a307.estimatevalidation.entity.RepairCode;
import com.ssafy.a307.estimatevalidation.entity.RepairCodeType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** canonical code 표시층. 행이 8개뿐이라 페이지네이션하지 않는다. */
public interface RepairCodeRepository extends JpaRepository<RepairCode, RepairCode.Key> {

    List<RepairCode> findByCodeTypeOrderByDisplayOrderAscCodeAsc(RepairCodeType codeType);

    List<RepairCode> findAllByOrderByCodeTypeAscDisplayOrderAsc();

    Optional<RepairCode> findByCodeTypeAndCode(RepairCodeType codeType, String code);

    /** 규칙 등록 시 "이 코드가 있고 활성인가" 를 확인한다. 표시층의 실제 소비 지점이다. */
    boolean existsByCodeTypeAndCodeAndActiveTrue(RepairCodeType codeType, String code);
}
