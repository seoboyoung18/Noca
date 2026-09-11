package com.ssafy.a307.admin.dto;

import com.ssafy.a307.audit.entity.AuditLog;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param rawName      견적서에 적히는 원문 그대로. PK 라 등록 후 바꿀 수 없고, 바꾸려면 지우고
 *                     다시 등록한다
 * @param partCode     대상 부품. <b>활성 코드만</b> 받는다 — 비활성 부품으로 매핑을 만들면
 *                     만들자마자 사전에서 빠져 아무 일도 하지 않는 행이 된다
 * @param changeReason 왜 이 별칭을 만드는지. <b>필수다</b> — 매핑은 이후 분석 결과의 부품
 *                     귀속을 바꾸는데, 행이 {@code (raw_name, part_code)} 두 칸뿐이라 나중에
 *                     보면 의도를 복원할 수 없다. 공백만 있는 값은 사유가 아니므로 거절한다
 */
public record PartNameMappingCreateRequest(
        @NotBlank(message = "원문 부품명은 필수입니다.")
        @Size(max = 200, message = "원문 부품명은 200자 이하여야 합니다.")
        String rawName,

        @NotBlank(message = "부품 코드는 필수입니다.")
        @Size(max = 50, message = "부품 코드는 50자 이하여야 합니다.")
        String partCode,

        @NotBlank(message = "변경 사유는 필수입니다.")
        @Size(max = AuditLog.MAX_CHANGE_REASON_LENGTH,
                message = "변경 사유는 500자 이하여야 합니다.")
        String changeReason) {

    public PartNameMappingCreateRequest {
        rawName = rawName == null ? null : rawName.strip();
        partCode = partCode == null ? null : partCode.strip();
        // strip 을 @NotBlank 보다 먼저 돌린다 — 공백만 있는 사유가 빈 문자열이 되어 걸린다.
        changeReason = changeReason == null ? null : changeReason.strip();
    }
}
