package com.ssafy.a307.admin.dto;

import com.ssafy.a307.estimatevalidation.entity.RepairCode;
import com.ssafy.a307.estimatevalidation.entity.RepairCodeType;

/**
 * @param code <b>바꿀 수 없다.</b> Java enum·DDL CHECK·통계 쿼리가 이 문자열을 공유한다
 */
public record RepairCodeAdminResponse(
        RepairCodeType codeType,
        String code,
        String displayName,
        short displayOrder,
        boolean active,
        long version) {

    public static RepairCodeAdminResponse from(RepairCode entity) {
        return new RepairCodeAdminResponse(
                entity.getCodeType(), entity.getCode(), entity.getDisplayName(),
                entity.getDisplayOrder(), entity.isActive(), entity.getVersion());
    }
}
