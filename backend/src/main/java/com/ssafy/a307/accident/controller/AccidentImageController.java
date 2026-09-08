package com.ssafy.a307.accident.controller;

import com.ssafy.a307.accident.dto.AccidentImageListResponse;
import com.ssafy.a307.accident.dto.ImageUploadCompleteRequest;
import com.ssafy.a307.accident.dto.ImageUploadCompleteResponse;
import com.ssafy.a307.accident.dto.ImageUploadUrlRequest;
import com.ssafy.a307.accident.dto.ImageUploadUrlResponse;
import com.ssafy.a307.accident.service.AccidentImageService;
import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.common.security.CurrentMemberProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사고 이미지 업로드 API. 인증 필수이며 회원 ID 는 {@link CurrentMemberProvider} 에서만 얻는다 —
 * 요청 본문·경로로 받지 않는다.
 *
 * <p>업로드는 <b>2단계</b>다. 바이트는 서버를 거치지 않는다.
 * <pre>
 * 1. POST .../images/upload-urls   서버가 제약을 검증하고 파일별 presigned PUT URL 을 준다
 * 2. PUT  {presigned url}          브라우저 → S3 직접. BE 무관
 * 3. POST .../images               완료 통보. 서버가 실제 오브젝트를 재검증하고 전처리한다
 * </pre>
 */
@RestController
@RequestMapping("/api/accidents/{accidentId}/images")
@RequiredArgsConstructor
public class AccidentImageController {

    private final AccidentImageService accidentImageService;
    private final CurrentMemberProvider currentMemberProvider;

    /** 발급은 {@code accident_image} 행을 새로 만들므로 201 이다. */
    @PostMapping("/upload-urls")
    public ResponseEntity<ApiResponse<ImageUploadUrlResponse>> issueUploadUrls(
            @PathVariable Long accidentId,
            @Valid @RequestBody ImageUploadUrlRequest request) {

        ImageUploadUrlResponse response = accidentImageService.issueUploadUrls(
                currentMemberProvider.currentMemberId(), accidentId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 완료 통보. 부분 실패를 허용하므로 일부 이미지가 실패해도 200 이고, 실패는 응답 본문의
     * 이미지별 {@code status} 로 나온다.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<ImageUploadCompleteResponse>> complete(
            @PathVariable Long accidentId,
            @Valid @RequestBody ImageUploadCompleteRequest request) {

        ImageUploadCompleteResponse response = accidentImageService.complete(
                currentMemberProvider.currentMemberId(), accidentId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 파일별 업로드 상태(Task 143). 화면을 새로 열어도 무엇이 아직 안 올라갔는지 알 수 있어야
     * 개별 재시도가 성립한다.
     * <p>
     * <b>API 명세서에 없는 엔드포인트다</b> — 목록·상태 조회 행이 없어 새로 추가했다.
     * 명세서 갱신 제안을 answer25 9장에 남겼다.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<AccidentImageListResponse>> list(@PathVariable Long accidentId) {
        AccidentImageListResponse response = accidentImageService.list(
                currentMemberProvider.currentMemberId(), accidentId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /** {@code VehicleController.delete} 와 같은 관례로 204 다. */
    @DeleteMapping("/{imageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long accidentId, @PathVariable Long imageId) {
        accidentImageService.delete(currentMemberProvider.currentMemberId(), accidentId, imageId);
    }
}
