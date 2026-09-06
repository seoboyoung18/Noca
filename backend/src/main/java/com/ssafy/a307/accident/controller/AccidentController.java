package com.ssafy.a307.accident.controller;

import com.ssafy.a307.accident.dto.AccidentCreateRequest;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    @PutMapping("/{accidentId}/actual-cost")
    public ResponseEntity<ApiResponse<ActualRepairCostResponse>> recordActualRepairCost(
            @PathVariable Long accidentId,
            @Valid @RequestBody ActualRepairCostRequest request) {
        ActualRepairCostResponse response = accidentService.recordActualRepairCost(
                currentMemberProvider.currentMemberId(), accidentId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
