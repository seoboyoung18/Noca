package com.ssafy.a307.analysis.callback;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 견적 항목 하나. 계약 {@code items[]} 의 원소이며 <b>부품 단위</b>다.
 *
 * <p>같은 부품이 여러 사진에 찍혔으면 AI 가 병합해 한 항목으로 보낸다. 어느 사진·어느 영역에서
 * 나왔는지는 {@link #detectionIds} 가 {@code imageResults[].detections[].detectionId} 를 가리켜
 * 이어 준다 — 화면에서 항목을 누르면 해당 영역을 강조하는 동작이 이 연결에 기댄다.
 *
 * <p><b>{@code VECTOR_ONLY} 검출은 여기 오지 않는다.</b> 계약이 "확정 부품이 아니므로 견적
 * {@code items[]} 에는 포함하지 않습니다" 라고 못박았다. 백엔드가 걸러내는 것이 아니라 AI 가
 * 보내지 않는다 — 검출 자체는 {@code imageResults} 에 남아 화면에 표시된다.
 *
 * @param confidence    부품 단위 대표값. {@code damaged_part.confidence} 가 NOT NULL 이라
 *                      계약 2차에서 추가됐다
 * @param repairMethod  AI 가 이미 고른 값이다. 후보 선택은 백엔드가 하지 않는다
 * @param partCost      <b>null 일 수 있다</b> — 원천 견적서에 부품비가 없는 경우다.
 *                      0 으로 바꾸지 않는다. 그러면 "부품비가 없다" 와 "0원이다" 가 섞인다
 * @param detectionIds  {@code "501:damage:damage-001"} 형식(2차 수정본에서 바뀜)
 * @param fallbackStage {@code MODEL}·{@code PRICE_TIER}·{@code ALL}. DEV corpus 는 모델 매핑
 *                      전이라 {@code PRICE_TIER} 부터 온다
 * @param mergedDamageTypes 같은 부위의 여러 엔트리를 대표 하나로 합쳤을 때 묶음 전체의 손상 유형
 *                      (S15P21A307-566). <b>합쳤을 때만 온다</b> — 긁힘 두 곳처럼 유형이 같아도
 *                      합쳤다면 온다. 사진엔 박스가 둘인데 표는 한 줄일 때 그 이유를 말해 준다
 */
public record CallbackItem(

        @NotBlank(message = "items[].partCode 는 필수입니다.")
        String partCode,

        @NotBlank(message = "items[].damageType 는 필수입니다.")
        String damageType,

        @NotNull(message = "items[].confidence 는 필수입니다.")
        @DecimalMin(value = "0.0", message = "confidence 는 0~1 입니다.")
        @DecimalMax(value = "1.0", message = "confidence 는 0~1 입니다.")
        BigDecimal confidence,

        String repairMethod,

        BigDecimal standardHq,

        @PositiveOrZero(message = "금액은 음수일 수 없습니다.")
        Integer partCost,

        @PositiveOrZero(message = "금액은 음수일 수 없습니다.")
        Integer laborCost,

        @PositiveOrZero(message = "금액은 음수일 수 없습니다.")
        Integer paintMaterialCost,

        @NotNull(message = "items[].itemTotal 은 필수입니다.")
        @PositiveOrZero(message = "금액은 음수일 수 없습니다.")
        Integer itemTotal,

        List<String> detectionIds,

        @NotNull(message = "items[].refCaseCount 는 필수입니다.")
        @PositiveOrZero(message = "참조 사례 수는 음수일 수 없습니다.")
        Integer refCaseCount,

        List<Long> referencedCaseIds,

        CallbackCostDistribution costDistribution,

        String fallbackStage,

        CallbackRepairMethodReason repairMethodReason,

        List<String> mergedDamageTypes) {

    public CallbackItem {
        // 새 선택 필드라 null 원소 하나로 콜백 전체가 깨지지 않게 걸러 둔다
        mergedDamageTypes = mergedDamageTypes == null
                ? List.of()
                : mergedDamageTypes.stream().filter(Objects::nonNull).toList();
        detectionIds = detectionIds == null ? List.of() : List.copyOf(detectionIds);
        referencedCaseIds = referencedCaseIds == null ? List.of() : List.copyOf(referencedCaseIds);
    }

    /** 사례 비용 분포. 실제 정산에 들어간 금액만 집계한 값이라 화면에 "실제 청구 기준" 이라 쓸 수 있다. */
    public record CallbackCostDistribution(Integer p25, Integer median, Integer p75) {
    }

    /**
     * 수리 방식 판단 근거.
     *
     * <p>{@code candidates} 는 확정값을 보내더라도 함께 남긴다 — S15P21A307-196 규칙이 선 뒤
     * 재판정할 때 근거가 된다. {@code reasonCode} 어휘는 아직 미확정이며 AI 소유다.
     */
    public record CallbackRepairMethodReason(List<String> candidates, String reasonCode) {
        public CallbackRepairMethodReason {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }
}
