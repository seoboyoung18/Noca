package com.ssafy.a307.admin.dto;

import com.ssafy.a307.estimatevalidation.entity.PartCodeScope;
import com.ssafy.a307.estimatevalidation.entity.PartNameMapping;

/**
 * @param normalizedName 정규화 결과. 런타임 사전의 실제 키다 — 관리자가 왜 두 별칭이
 *                       충돌하는지 눈으로 확인할 수 있어야 한다
 * @param partCodeScope  대상 부품이 AI 핵심 32종({@code AI_LABEL})인지 견적 확장 코드({@code EXTENDED})인지.
 *                       매핑 화면이 부품 코드를 다시 조회하지 않고도 "32종 매핑" 을 구분하게 한다
 * @param partActive     대상 부품의 활성 상태. {@code false} 면 이 매핑은 <b>사전에 올라가지 않는다</b>
 * @param inDictionary   지금 런타임 사전에 실제로 들어가는가. 부품이 비활성이거나 정규화 키가
 *                       다른 부품과 충돌하면 {@code false} 다 — 조용히 빠지는 항목을 숨기지 않는다
 */
public record PartNameMappingAdminResponse(
        String rawName,
        String normalizedName,
        String partCode,
        String partNameKo,
        PartCodeScope partCodeScope,
        boolean partActive,
        boolean inDictionary) {

    public static PartNameMappingAdminResponse of(PartNameMapping mapping, String normalizedName,
                                                  boolean inDictionary) {
        return new PartNameMappingAdminResponse(
                mapping.getRawName(),
                normalizedName,
                mapping.getPartCode().getPartCode(),
                mapping.getPartCode().getNameKo(),
                mapping.getPartCode().getCodeScope(),
                mapping.getPartCode().isActive(),
                inDictionary);
    }
}
