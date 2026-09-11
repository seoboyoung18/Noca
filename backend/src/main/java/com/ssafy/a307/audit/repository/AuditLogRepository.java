package com.ssafy.a307.audit.repository;

import com.ssafy.a307.audit.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * 이력은 쓰고 읽기만 한다 — {@code delete*}·{@code save} 외의 변경 메서드를 노출하지 않는다.
 *
 * <p>필터가 여섯 가지 조합(기간·행위자·행위·대상 종류·대상 ID)이라
 * {@link JpaSpecificationExecutor} 로 조건을 조립한다. 조합마다 메서드를 만들면 32개가 된다.
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long>,
        JpaSpecificationExecutor<AuditLog> {
}
