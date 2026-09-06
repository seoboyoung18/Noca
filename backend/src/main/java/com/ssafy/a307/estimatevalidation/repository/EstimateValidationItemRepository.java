package com.ssafy.a307.estimatevalidation.repository;

import com.ssafy.a307.estimatevalidation.entity.EstimateValidationItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EstimateValidationItemRepository extends JpaRepository<EstimateValidationItem, Long> {
    List<EstimateValidationItem> findByValidation_ValidationIdOrderByLineNoAsc(Long validationId);
}
