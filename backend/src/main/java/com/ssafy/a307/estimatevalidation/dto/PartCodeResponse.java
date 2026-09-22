package com.ssafy.a307.estimatevalidation.dto;

import com.ssafy.a307.estimatevalidation.entity.PartCode;

/**
 * 사용자가 고를 수 있는 부위 하나 (S15P21A307-568).
 *
 * <p>관리자 응답({@code PartCodeAdminResponse})과 따로 둔다 — 범위·버전·활성 여부는 관리 화면의
 * 관심사이고, 사용자 화면은 고를 이름과 위치만 있으면 된다.
 *
 * @param layoutZone 앞·뒤·옆 같은 위치. 화면이 스크롤 목록을 이 값으로 묶는다
 */
public record PartCodeResponse(String partCode, String nameKo, String layoutZone) {

    public static PartCodeResponse from(PartCode partCode) {
        return new PartCodeResponse(partCode.getPartCode(), partCode.getNameKo(), partCode.getLayoutZone());
    }
}
