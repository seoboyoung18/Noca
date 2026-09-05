package com.ssafy.a307.vehicle.controller;

import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.common.security.CurrentMemberProvider;
import com.ssafy.a307.vehicle.dto.VehicleCreateRequest;
import com.ssafy.a307.vehicle.dto.VehicleListResponse;
import com.ssafy.a307.vehicle.dto.VehicleResponse;
import com.ssafy.a307.vehicle.dto.VehicleUpdateRequest;
import com.ssafy.a307.vehicle.service.VehicleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vehicles")
@RequiredArgsConstructor
public class VehicleController {

    private final VehicleService vehicleService;
    private final CurrentMemberProvider currentMemberProvider;

    @PostMapping
    public ResponseEntity<ApiResponse<VehicleResponse>> create(
            @Valid @RequestBody VehicleCreateRequest request) {

        VehicleResponse response =
                vehicleService.create(currentMemberProvider.currentMemberId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @GetMapping("/me")
    public ApiResponse<VehicleListResponse> findMine() {
        return ApiResponse.of(new VehicleListResponse(
                vehicleService.findMine(currentMemberProvider.currentMemberId())));
    }

    @PatchMapping("/{vehicleId}")
    public ApiResponse<VehicleResponse> update(@PathVariable Long vehicleId,
                                               @Valid @RequestBody VehicleUpdateRequest request) {

        return ApiResponse.of(
                vehicleService.update(currentMemberProvider.currentMemberId(), vehicleId, request));
    }

    @DeleteMapping("/{vehicleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long vehicleId) {
        vehicleService.delete(currentMemberProvider.currentMemberId(), vehicleId);
    }
}
