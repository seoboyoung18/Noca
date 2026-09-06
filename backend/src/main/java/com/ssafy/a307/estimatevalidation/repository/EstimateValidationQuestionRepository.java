package com.ssafy.a307.estimatevalidation.repository;

import com.ssafy.a307.estimatevalidation.entity.EstimateValidationQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EstimateValidationQuestionRepository extends JpaRepository<EstimateValidationQuestion, Long> {
    List<EstimateValidationQuestion> findByValidation_ValidationIdOrderByDisplayOrderAsc(Long validationId);
}
