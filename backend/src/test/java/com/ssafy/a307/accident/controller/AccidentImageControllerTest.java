package com.ssafy.a307.accident.controller;

import com.ssafy.a307.accident.dto.AccidentImageAssetResponse;
import com.ssafy.a307.accident.dto.AccidentImageListResponse;
import com.ssafy.a307.accident.dto.AccidentImageResponse;
import com.ssafy.a307.accident.dto.AccidentImageResultResponse;
import com.ssafy.a307.accident.dto.ImageProcessingStatus;
import com.ssafy.a307.accident.dto.ImageUploadCompleteResponse;
import com.ssafy.a307.accident.dto.ImageUploadState;
import com.ssafy.a307.accident.dto.ImageUploadUrlResponse;
import com.ssafy.a307.accident.dto.IssuedUploadUrl;
import com.ssafy.a307.accident.entity.ImageQualityStatus;
import com.ssafy.a307.accident.entity.ImageVariant;
import com.ssafy.a307.accident.service.AccidentImageService;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.common.security.CurrentMemberProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 계약 검증 — 상태 코드, {@code {data}}/{@code {error}} 응답 형태, Bean Validation.
 * {@code AccidentControllerTest} 와 같은 방식이다.
 */
@WebMvcTest(AccidentImageController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("AccidentImageController")
class AccidentImageControllerTest {

    private static final long ME = 1L;
    private static final long ACCIDENT_ID = 7L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccidentImageService accidentImageService;
    @MockitoBean
    private CurrentMemberProvider currentMemberProvider;

    @BeforeEach
    void setUp() {
        given(currentMemberProvider.currentMemberId()).willReturn(ME);
    }

    @Test
    @DisplayName("POST .../images/upload-urls — 201 과 파일별 발급 정보를 준다")
    void issueUploadUrls() throws Exception {
        given(accidentImageService.issueUploadUrls(eq(ME), eq(ACCIDENT_ID), any()))
                .willReturn(new ImageUploadUrlResponse(1, 20, 19, List.of(new IssuedUploadUrl(
                        11L, "front.jpg", "FRONT", "accidents/7/images/11/original.jpg",
                        "https://storage.test/accidents/7/images/11/original.jpg?signed=1",
                        "PUT", Map.of("Content-Type", "image/jpeg"),
                        Instant.parse("2026-09-07T13:00:00Z")))));

        mockMvc.perform(post("/api/accidents/{accidentId}/images/upload-urls", ACCIDENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "files": [
                                    { "originalFilename": "front.jpg", "contentType": "image/jpeg",
                                      "size": 1024, "angleCode": "FRONT" }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.issuedCount").value(1))
                .andExpect(jsonPath("$.data.maxCountPerAccident").value(20))
                .andExpect(jsonPath("$.data.remainingSlots").value(19))
                .andExpect(jsonPath("$.data.files[0].imageId").value(11))
                .andExpect(jsonPath("$.data.files[0].s3Key").value("accidents/7/images/11/original.jpg"))
                .andExpect(jsonPath("$.data.files[0].uploadMethod").value("PUT"))
                .andExpect(jsonPath("$.data.files[0].angleCode").value("FRONT"))
                .andExpect(jsonPath("$.data.files[0].requiredHeaders['Content-Type']")
                        .value("image/jpeg"));
    }

    @Test
    @DisplayName("POST .../images/upload-urls — files 가 비면 400 이고 서비스를 부르지 않는다")
    void issueUploadUrlsRejectsEmptyFiles() throws Exception {
        mockMvc.perform(post("/api/accidents/{accidentId}/images/upload-urls", ACCIDENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "files": [] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        then(accidentImageService).should(never()).issueUploadUrls(any(), any(), any());
    }

    @Test
    @DisplayName("POST .../images/upload-urls — 파일 정보가 빠지면 400 이다")
    void issueUploadUrlsRejectsIncompleteFile() throws Exception {
        mockMvc.perform(post("/api/accidents/{accidentId}/images/upload-urls", ACCIDENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "files": [ { "originalFilename": "front.jpg" } ] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        then(accidentImageService).should(never()).issueUploadUrls(any(), any(), any());
    }

    @Test
    @DisplayName("POST .../images/upload-urls — 남의 사고는 404 와 error 봉투다")
    void issueUploadUrlsNotFound() throws Exception {
        willThrow(new BusinessException(ErrorCode.NOT_FOUND, "사고를 찾을 수 없습니다."))
                .given(accidentImageService).issueUploadUrls(eq(ME), eq(ACCIDENT_ID), any());

        mockMvc.perform(post("/api/accidents/{accidentId}/images/upload-urls", ACCIDENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "files": [ { "originalFilename": "front.jpg",
                                  "contentType": "image/jpeg", "size": 1024 } ] }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("사고를 찾을 수 없습니다."))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("POST .../images — 200 과 이미지별 결과를 준다. 부분 실패도 200 이다")
    void complete() throws Exception {
        given(accidentImageService.complete(eq(ME), eq(ACCIDENT_ID), any()))
                .willReturn(ImageUploadCompleteResponse.of(List.of(
                        new AccidentImageResultResponse(
                                11L, "front.jpg", ImageProcessingStatus.COMPLETED,
                                ImageQualityStatus.PASS, null, null, null,
                                List.of(new AccidentImageAssetResponse(
                                        ImageVariant.ORIGINAL, "accidents/7/images/11/original.jpg",
                                        400, 300, 2048))),
                        AccidentImageResultResponse.failed(
                                12L, "rear.jpg", "SIZE_MISMATCH", "신고한 크기와 실제 업로드 크기가 다릅니다."))));

        mockMvc.perform(post("/api/accidents/{accidentId}/images", ACCIDENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "images": [ { "imageId": 11 }, { "imageId": 12, "size": 2048 } ] }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requested").value(2))
                .andExpect(jsonPath("$.data.succeeded").value(1))
                .andExpect(jsonPath("$.data.failed").value(1))
                .andExpect(jsonPath("$.data.results[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.results[0].qualityStatus").value("PASS"))
                .andExpect(jsonPath("$.data.results[0].assets[0].variant").value("ORIGINAL"))
                .andExpect(jsonPath("$.data.results[1].status").value("FAILED"))
                .andExpect(jsonPath("$.data.results[1].failureCode").value("SIZE_MISMATCH"));
    }

    @Test
    @DisplayName("POST .../images — images 가 비면 400 이다")
    void completeRejectsEmptyImages() throws Exception {
        mockMvc.perform(post("/api/accidents/{accidentId}/images", ACCIDENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "images": [] }
                                """))
                .andExpect(status().isBadRequest());

        then(accidentImageService).should(never()).complete(any(), any(), any());
    }

    @Test
    @DisplayName("POST .../images — 저장소 미구성은 503 이다. 가짜 성공을 만들지 않는다")
    void completeWithoutStorageAdapter() throws Exception {
        willThrow(new BusinessException(
                ErrorCode.SERVICE_UNAVAILABLE, "이미지 저장소 공급자가 구성되지 않았습니다."))
                .given(accidentImageService).complete(eq(ME), eq(ACCIDENT_ID), any());

        mockMvc.perform(post("/api/accidents/{accidentId}/images", ACCIDENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "images": [ { "imageId": 11 } ] }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("GET .../images — 완료·대기 개수와 파일 목록을 준다")
    void list() throws Exception {
        given(accidentImageService.list(ME, ACCIDENT_ID))
                .willReturn(AccidentImageListResponse.of(List.of(
                        new AccidentImageResponse(
                                11L, "front.jpg", ImageUploadState.COMPLETED,
                                ImageQualityStatus.WARN, "해상도 부족 — 짧은 변 300px (기준 720px)",
                                Instant.parse("2026-09-07T13:00:00Z"), List.of()),
                        new AccidentImageResponse(
                                12L, "rear.jpg", ImageUploadState.PENDING,
                                ImageQualityStatus.PASS, null,
                                Instant.parse("2026-09-07T13:01:00Z"), List.of())), 20, 18));

        mockMvc.perform(get("/api/accidents/{accidentId}/images", ACCIDENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.completed").value(1))
                .andExpect(jsonPath("$.data.pending").value(1))
                .andExpect(jsonPath("$.data.remainingSlots").value(18))
                .andExpect(jsonPath("$.data.images[0].uploadState").value("COMPLETED"))
                .andExpect(jsonPath("$.data.images[0].qualityStatus").value("WARN"))
                .andExpect(jsonPath("$.data.images[0].qualityReason").exists())
                .andExpect(jsonPath("$.data.images[1].uploadState").value("PENDING"));
    }

    @Test
    @DisplayName("DELETE .../images/{imageId} — 204 이고 본문이 없다")
    void deleteImage() throws Exception {
        mockMvc.perform(delete("/api/accidents/{accidentId}/images/{imageId}", ACCIDENT_ID, 11L))
                .andExpect(status().isNoContent());

        then(accidentImageService).should().delete(ME, ACCIDENT_ID, 11L);
    }

    @Test
    @DisplayName("경로 변수 타입이 맞지 않으면 400 이다")
    void invalidPathVariable() throws Exception {
        mockMvc.perform(get("/api/accidents/{accidentId}/images", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}
