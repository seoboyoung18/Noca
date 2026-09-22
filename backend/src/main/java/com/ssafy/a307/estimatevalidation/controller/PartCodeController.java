package com.ssafy.a307.estimatevalidation.controller;

import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.estimatevalidation.dto.PartCodeResponse;
import com.ssafy.a307.estimatevalidation.entity.PartCodeScope;
import com.ssafy.a307.estimatevalidation.repository.PartCodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 사용자가 고를 수 있는 부위 목록 (S15P21A307-568). 로그인한 회원이면 누구나 볼 수 있다.
 *
 * <p>파손은 찾았는데 부품을 못 찾은 분석에서, 사용자가 차량 선택처럼 스크롤로 부위를 골라
 * 이어서 분석하는 데 쓴다. 관리자 목록({@code /api/admin/part-codes})과 달리 조건 검색이 없다.
 *
 * <p><b>AI 라벨 부위만 내보낸다.</b> 확장 코드({@code EXTENDED})는 정비소 견적서 항목을 받아
 * 적기 위한 것이라 유사 사례 검색 코퍼스에 없다 — 골라도 분석이 이어지지 않는다.
 * 비활성 부위도 뺀다.
 */
@RestController
@RequestMapping("/api/part-codes")
@RequiredArgsConstructor
public class PartCodeController {

    private final PartCodeRepository partCodeRepository;

    /** 표시 순서대로. 같은 순서면 코드 순이다 — 요청마다 목록 순서가 흔들리지 않게. */
    @GetMapping
    @Transactional(readOnly = true)
    public ApiResponse<List<PartCodeResponse>> list() {
        return ApiResponse.of(partCodeRepository
                .findByActiveTrueAndCodeScopeOrderByDisplayOrderAscPartCodeAsc(PartCodeScope.AI_LABEL)
                .stream()
                .map(PartCodeResponse::from)
                .toList());
    }
}
