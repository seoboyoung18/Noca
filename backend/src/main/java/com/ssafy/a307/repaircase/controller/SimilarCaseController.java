package com.ssafy.a307.repaircase.controller;

import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.repaircase.dto.SimilarCaseResponse;
import com.ssafy.a307.repaircase.service.SimilarCaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 유사 사례 조회.
 *
 * <p><b>검색 API 가 아니다.</b> AI 서버가 찾아 준 사례를 보여 주기만 한다(S15P21A307-236).
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SimilarCaseController {

    private final SimilarCaseService similarCaseService;

    /**
     * 항목이 참고한 유사 사례.
     *
     * @param estimateItemId <b>필수다.</b> 항목마다 참고한 사례가 다르고, 지금 화면에는 항목별
     *                       진입점("이 사례들 보기")만 있다. 견적 전체 사례를 보여 주는 화면이
     *                       생기면 선택으로 완화하면 되고, 그 변경은 기존 호출을 깨뜨리지 않는다.
     *                       <p>
     *                       <b>{@code required = true} 를 쓰지 않고 직접 검사한다.</b> 그러면
     *                       Spring 이 {@code MissingServletRequestParameterException} 을 던지는데,
     *                       공통 예외 핸들러가 그 타입을 다루지 않아 500 으로 나간다. 클라이언트
     *                       잘못을 서버 오류로 알리면 원인을 못 찾는다
     */
    @GetMapping("/estimates/{estimateId}/similar-cases")
    public ApiResponse<SimilarCaseResponse> ofItem(@PathVariable Long estimateId,
                                                   @RequestParam(required = false) Long estimateItemId,
                                                   @AuthenticationPrincipal UserPrincipal principal) {

        if (estimateItemId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "estimateItemId 는 필수입니다.");
        }
        return ApiResponse.of(
                similarCaseService.ofItem(estimateId, estimateItemId, principal.getMemberId()));
    }
}
