package com.ssafy.a307.analysis.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * 검출 하나 — 부품 한 곳의 손상 한 건.
 *
 * <p><b>{@code detections} 길이는 원본 {@code predictions} 길이와 다르다.</b> 매칭에 실패했거나
 * 격리된 예측이 표준화 과정에서 빠진다. 줄어든 것이 적재 실패가 아니다.
 *
 * <p><b>{@code work_decision} 은 {@code "CANDIDATE"} 고정이다.</b> 즉 {@code work_candidates} 는
 * 확정이 아니라 <b>후보</b>다. 그런데 {@code damaged_part.repair_method} 는 NOT NULL 이라
 * 하나를 골라야 한다. 후보가 둘일 때 무엇을 고를지는 심각도가 가르는데 그 규칙이
 * {@code S15P21A307-197} 로 롤백됐으므로, <b>후보가 하나일 때만 적재하고 둘 이상이면 보류한다.</b>
 * 판단은 {@code AnalysisResultIngestService} 에 있다.
 */
public record Detection(

        @JsonProperty("detection_id")
        String detectionId,

        @NotNull(message = "detection.part 는 필수입니다.")
        @Valid
        @JsonProperty("part")
        DetectedPart part,

        @NotNull(message = "detection.damage 는 필수입니다.")
        @Valid
        @JsonProperty("damage")
        DetectedDamage damage,

        @NotNull(message = "detection.geometry 는 필수입니다.")
        @Valid
        @JsonProperty("geometry")
        DetectionGeometry geometry,

        @NotNull(message = "detection.confidence 는 필수입니다. 값이 비어도 키는 있어야 합니다.")
        @Valid
        @JsonProperty("confidence")
        DetectionConfidence confidence,

        @NotEmpty(message = "work_candidates 는 최소 하나여야 합니다.")
        @Valid
        @JsonProperty("work_candidates")
        List<WorkCandidate> workCandidates,

        @NotNull(message = "work_decision 은 필수입니다.")
        @Pattern(regexp = "CANDIDATE", message = "work_decision 은 CANDIDATE 고정입니다.")
        @JsonProperty("work_decision")
        String workDecision,

        @NotBlank(message = "work_rule_version 은 필수입니다.")
        @JsonProperty("work_rule_version")
        String workRuleVersion
) {

    public Detection {
        workCandidates = workCandidates == null ? null : List.copyOf(workCandidates);
    }

    /** 후보가 정확히 하나여서 수리 방식을 근거 있게 정할 수 있는가. */
    public boolean hasSingleWorkCandidate() {
        return workCandidates != null && workCandidates.size() == 1;
    }
}
