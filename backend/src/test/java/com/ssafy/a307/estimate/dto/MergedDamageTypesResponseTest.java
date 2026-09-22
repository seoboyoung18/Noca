package com.ssafy.a307.estimate.dto;

import com.ssafy.a307.estimate.domain.FallbackStage;
import com.ssafy.a307.estimate.domain.RefCondition;
import com.ssafy.a307.estimate.repository.EstimateQueryRepository.EstimateBasisItemView;
import com.ssafy.a307.estimate.repository.EstimateQueryRepository.EstimateItemView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 합친 손상 유형 응답 (S15P21A307-566).
 *
 * <p>AI 가 같은 부위의 손상 여러 곳을 대표 하나로 합치면, 사진엔 박스가 둘인데 표는 한 줄이 된다.
 * 그 이유("긁힘·깨짐")를 화면이 말할 수 있도록 항목과 근거 응답이 같은 값을 싣는지 본다.
 */
@DisplayName("합친 손상 유형 응답")
class MergedDamageTypesResponseTest {

    @Test
    @DisplayName("견적 항목과 산정 근거가 같은 합친 손상 유형을 싣는다")
    void itemAndBasisCarryTheSameTypes() {
        List<String> merged = List.of("Scratched", "Breakage");

        EstimateItemView itemView = mock(EstimateItemView.class);
        given(itemView.getPartCode()).willReturn("FRONT_BUMPER");
        given(itemView.getRepairMethod()).willReturn("exchange");

        EstimateBasisItemView basisView = mock(EstimateBasisItemView.class);
        given(basisView.getPartCode()).willReturn("FRONT_BUMPER");
        given(basisView.getRepairMethod()).willReturn("exchange");
        RefCondition basis = new RefCondition(FallbackStage.MODEL, null, null, null, null,
                List.of(121381L), merged);

        assertThat(EstimateItemResponse.from(itemView, merged).mergedDamageTypes())
                .containsExactly("Scratched", "Breakage");
        assertThat(EstimateBasisItemResponse.of(basisView, basis).mergedDamageTypes())
                .containsExactly("Scratched", "Breakage");
    }

    /** 화면이 "비어 있지 않으면 합친 항목" 으로만 판단하면 되게 {@code null} 로 내보내지 않는다. */
    @Test
    @DisplayName("합치지 않은 항목은 빈 배열로 나간다 — null 이 아니다")
    void emptyArrayWhenNotMerged() {
        EstimateItemResponse fromView = EstimateItemResponse.from(mock(EstimateItemView.class), null);
        EstimateItemResponse legacyShape = new EstimateItemResponse(1L, "FRONT_BUMPER", "앞 범퍼", "FRONT",
                "Scratched", "coating", "도장", null, null, null, null, 100, 100, 100, 3, false);

        assertThat(fromView.mergedDamageTypes()).isEmpty();
        assertThat(legacyShape.mergedDamageTypes()).isEmpty();

        JsonNode json = new ObjectMapper().valueToTree(legacyShape);
        assertThat(json.get("mergedDamageTypes").isArray()).isTrue();
        assertThat(json.get("mergedDamageTypes").size()).isZero();
    }
}
