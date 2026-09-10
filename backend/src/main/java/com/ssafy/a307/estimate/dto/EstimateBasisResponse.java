package com.ssafy.a307.estimate.dto;

import java.util.List;

/**
 * 견적 하나의 산정 근거. 항목별 근거를 모아 준다.
 *
 * <p><b>견적 조회와 나눈 이유</b> — 근거는 "자세히 보기"로 펼쳐 보는 것이라 화면 첫 장에
 * 필요하지 않고, {@code ref_condition} 이 JSONB 라 항목마다 응답이 커진다. 항목 목록 자체는
 * 항상 필요해서 견적 조회에 담았지만(S15P21A307-261) 근거는 그렇지 않다.
 *
 * <p><b>버전을 함께 준다.</b> 재산정하면 근거도 함께 바뀐다. 어느 버전의 근거인지 밝히지
 * 않으면 화면이 옛 견적에 새 근거를 붙여 보여 줄 수 있다.
 */
public record EstimateBasisResponse(
        Long estimateId,
        short version,
        List<EstimateBasisItemResponse> items) {
}
