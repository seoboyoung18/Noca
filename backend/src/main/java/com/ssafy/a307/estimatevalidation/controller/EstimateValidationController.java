package com.ssafy.a307.estimatevalidation.controller;

import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.common.security.CurrentMemberProvider;
import com.ssafy.a307.estimatevalidation.dto.FileValidationMetadata;
import com.ssafy.a307.estimatevalidation.dto.ManualValidationRequest;
import com.ssafy.a307.estimatevalidation.dto.ValidationAcceptedResponse;
import com.ssafy.a307.estimatevalidation.dto.ValidationHistoryResponse;
import com.ssafy.a307.estimatevalidation.dto.ValidationQuestionResponse;
import com.ssafy.a307.estimatevalidation.dto.ValidationResultResponse;
import com.ssafy.a307.estimatevalidation.dto.ValidationStatusResponse;
import com.ssafy.a307.estimatevalidation.service.EstimateFileValidationService;
import com.ssafy.a307.estimatevalidation.service.EstimateValidationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/estimate-validations")
@RequiredArgsConstructor
public class EstimateValidationController {

    private final EstimateValidationService validationService;
    private final EstimateFileValidationService fileValidationService;
    private final CurrentMemberProvider currentMemberProvider;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<ValidationAcceptedResponse>> registerManual(
            @Valid @RequestBody ManualValidationRequest request) {
        var response = validationService.registerManual(currentMemberProvider.currentMemberId(), request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(response));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ValidationAcceptedResponse>> registerFile(
            @Valid @RequestPart("metadata") FileValidationMetadata metadata,
            @RequestPart("file") MultipartFile file) {
        var response = fileValidationService.registerFile(
                currentMemberProvider.currentMemberId(), metadata, file);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(response));
    }

    @GetMapping("/{validationId}")
    public ApiResponse<ValidationStatusResponse> status(@PathVariable Long validationId) {
        return ApiResponse.of(validationService.status(currentMemberProvider.currentMemberId(), validationId));
    }

    @GetMapping("/{validationId}/result")
    public ApiResponse<ValidationResultResponse> result(@PathVariable Long validationId) {
        return ApiResponse.of(validationService.result(currentMemberProvider.currentMemberId(), validationId));
    }

    @GetMapping("/{validationId}/questions")
    public ApiResponse<List<ValidationQuestionResponse>> questions(@PathVariable Long validationId) {
        return ApiResponse.of(validationService.questions(currentMemberProvider.currentMemberId(), validationId));
    }

    @GetMapping("/me")
    public ApiResponse<ValidationHistoryResponse> history(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.of(validationService.history(currentMemberProvider.currentMemberId(), page, size));
    }

    @GetMapping("/{validationId}/pdf")
    public ResponseEntity<Void> pdf(@PathVariable Long validationId) {
        URI location = fileValidationService.pdfDownload(currentMemberProvider.currentMemberId(), validationId);
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location.toString()).build();
    }

    @DeleteMapping("/{validationId}")
    public ResponseEntity<Void> delete(@PathVariable Long validationId) {
        fileValidationService.delete(currentMemberProvider.currentMemberId(), validationId);
        return ResponseEntity.noContent().build();
    }
}
