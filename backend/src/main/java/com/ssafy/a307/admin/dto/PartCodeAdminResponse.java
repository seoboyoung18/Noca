package com.ssafy.a307.admin.dto;

import com.ssafy.a307.estimatevalidation.entity.PartCode;
import com.ssafy.a307.estimatevalidation.entity.PartCodeScope;

/**
 * @param codeScope       {@code AI_LABEL}(파손 검출 모델 라벨) 또는 {@code EXTENDED}(견적 확장 코드)
 * @param mappingCount    이 부품을 가리키는 한글 별칭 수. 비활성화·삭제 영향 범위를 화면이 먼저 알려 준다
 */
public record PartCodeAdminResponse(
        String partCode,
        String nameKo,
        String layoutZone,
        short displayOrder,
        PartCodeScope codeScope,
        boolean active,
        long mappingCount,
        long version) {

    public static PartCodeAdminResponse from(PartCode code, long mappingCount) {
        return new PartCodeAdminResponse(
                code.getPartCode(), code.getNameKo(), code.getLayoutZone(),
                code.getDisplayOrder(), code.getCodeScope(), code.isActive(),
                mappingCount, code.getVersion());
    }
}
