package com.ssafy.a307.accident.controller;

import com.ssafy.a307.accident.dto.AccidentCreateRequest;
import com.ssafy.a307.accident.dto.AccidentPageResponse;
import com.ssafy.a307.accident.dto.AccidentResponse;
import com.ssafy.a307.accident.dto.ActualRepairCostRequest;
import com.ssafy.a307.accident.dto.ActualRepairCostResponse;
import com.ssafy.a307.accident.service.AccidentService;
import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.common.security.CurrentMemberProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accidents")
@RequiredArgsConstructor
public class AccidentController {

    private final AccidentService accidentService;
    private final CurrentMemberProvider currentMemberProvider;

    @PostMapping
    public ResponseEntity<ApiResponse<AccidentResponse>> create(
            @Valid @RequestBody AccidentCreateRequest request) {

        AccidentResponse response =
                accidentService.create(currentMemberProvider.currentMemberId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /** 리터럴 경로인 {@code /me} 가 {@code {accidentId}} 보다 먼저 매칭된다 — 7-1 테스트로 고정한다. */
    /**
     * 사고 이력 목록. 페이지 파라미터는 생략할 수 있다 — 없으면 첫 페이지 20건이다.
     *
     * <p>응답의 {@code accidents} 키는 페이지네이션 도입 전과 같다. FE 인수인계 문서가 이미
     * 그 키를 계약으로 적었으므로 유지하고, 페이지 메타(page·size·totalElements·totalPages·hasNext)만
     * 덧붙인다. 기존 호출자는 고치지 않아도 계속 동작한다.
     */
    @GetMapping("/me")
    public ApiResponse<AccidentPageResponse> findMine(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.of(accidentService.findMinePaged(
                currentMemberProvider.currentMemberId(), page, size));
    }

    @GetMapping("/{accidentId}")
    public ApiResponse<AccidentResponse> findOne(@PathVariable Long accidentId) {
        return ApiResponse.of(
                accidentService.findOne(currentMemberProvider.currentMemberId(), accidentId));
    }

    @PutMapping("/{accidentId}/actual-cost")
    public ResponseEntity<ApiResponse<ActualRepairCostResponse>> recordActualRepairCost(
            @PathVariable Long accidentId,
            @Valid @RequestBody ActualRepairCostRequest request) {
        ActualRepairCostResponse response = accidentService.recordActualRepairCost(
                currentMemberProvider.currentMemberId(), accidentId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
