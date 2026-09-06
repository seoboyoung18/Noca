package com.ssafy.a307.estimatevalidation.dto;

import com.ssafy.a307.estimatevalidation.domain.ValidationFlag;
import com.ssafy.a307.estimatevalidation.entity.EstimateValidationQuestion;

public record ValidationQuestionResponse(
        Long questionId,
        Long validationItemId,
        Short lineNo,
        ValidationFlag sourceFlag,
        String text,
        Short displayOrder) {

    public static ValidationQuestionResponse from(EstimateValidationQuestion question) {
        return new ValidationQuestionResponse(
                question.getQuestionId(),
                question.getValidationItem() == null ? null : question.getValidationItem().getValidationItemId(),
                question.getValidationItem() == null ? null : question.getValidationItem().getLineNo(),
                question.getSourceFlag(),
                question.getQuestionText(),
                question.getDisplayOrder());
    }
}
