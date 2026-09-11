package com.ssafy.a307.estimate.controller;

import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.estimate.dto.EstimatePdfStatusResponse;
import com.ssafy.a307.estimate.pdf.EstimatePdfService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 견적 PDF (S15P21A307-339·341·342). 내용은 사고 분석 견적 리포트(336)를 그대로 그린 것이다.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class EstimatePdfController {

    private final EstimatePdfService pdfService;

    /** 202 — 생성은 워커가 한다. 진행 중인 요청이 있으면 409. */
    @PostMapping("/estimates/{estimateId}/pdf")
    public ResponseEntity<ApiResponse<EstimatePdfStatusResponse>> request(
            @PathVariable Long estimateId, @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.of(pdfService.request(estimateId, principal.getMemberId())));
    }

    /** 가장 최근 요청의 상태. 요청한 적이 없으면 {@code data} 가 null 이다. */
    @GetMapping("/estimates/{estimateId}/pdf")
    public ApiResponse<EstimatePdfStatusResponse> status(
            @PathVariable Long estimateId, @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.of(pdfService.status(estimateId, principal.getMemberId()));
    }

    /** 302 + 서명된 조회 URL(5분). 완료본이 없으면 409. */
    @GetMapping("/estimates/{estimateId}/pdf/download")
    public ResponseEntity<Void> download(
            @PathVariable Long estimateId, @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION,
                        pdfService.download(estimateId, principal.getMemberId()).toString())
                .build();
    }
}
