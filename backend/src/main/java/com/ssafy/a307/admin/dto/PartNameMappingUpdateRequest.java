package com.ssafy.a307.admin.dto;

import com.ssafy.a307.audit.entity.AuditLog;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 매핑 대상만 바꾼다. <b>{@code rawName} 은 PK 라 여기 없다</b> — 원문을 바꾸는 것은
 * 다른 별칭을 만드는 일이므로 삭제 후 재등록이다. 어떤 행을 고칠지는
 * {@code ?rawName=} 쿼리 파라미터가 지정한다({@code AdminPartNameMappingController} 참고 —
 * 원문에 {@code /} 가 들어가는 행이 346건이라 경로 변수로 표현할 수 없다).
 *
 * <p>매핑 행에는 {@code version} 이 없다. 바꿀 수 있는 필드가 {@code partCode} 하나뿐이라
 * lost update 로 잃을 것이 없고, 동시에 다른 코드로 바꾸면 마지막 값이 곧 관리자의 의도다.
 * 무엇을 잃었는지는 감사 이력의 before/after 로 남는다.
 *
 * @param changeReason 왜 대상 부품을 바꾸는지. <b>필수다</b> — 이 변경은 이후 분석의 부품
 *                     귀속을 바꾸면서도 과거 {@code repair_case_item.part_code} 는 그대로
 *                     두므로, 나중에 두 값이 다른 이유를 이력에서만 알 수 있다
 */
public record PartNameMappingUpdateRequest(
        @NotBlank(message = "부품 코드는 필수입니다.")
        @Size(max = 50, message = "부품 코드는 50자 이하여야 합니다.")
        String partCode,

        @NotBlank(message = "변경 사유는 필수입니다.")
        @Size(max = AuditLog.MAX_CHANGE_REASON_LENGTH,
                message = "변경 사유는 500자 이하여야 합니다.")
        String changeReason) {

    public PartNameMappingUpdateRequest {
        partCode = partCode == null ? null : partCode.strip();
        changeReason = changeReason == null ? null : changeReason.strip();
    }
}
