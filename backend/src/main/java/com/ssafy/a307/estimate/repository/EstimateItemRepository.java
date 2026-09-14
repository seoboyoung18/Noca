package com.ssafy.a307.estimate.repository;

import com.ssafy.a307.estimate.entity.EstimateItem;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 견적 항목 쓰기 (S15P21A307-157).
 *
 * <p><b>쓰기 전용이다.</b> 조회는 {@link EstimateQueryRepository} 가 {@code part_code} 를
 * 조인해 화면용 모양으로 가져간다.
 */
public interface EstimateItemRepository extends JpaRepository<EstimateItem, Long> {
}
