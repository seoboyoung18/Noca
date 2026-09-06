package com.ssafy.a307.estimatevalidation.controller;

import com.ssafy.a307.common.security.CurrentMemberProvider;
import com.ssafy.a307.estimatevalidation.domain.EstimateFileType;
import com.ssafy.a307.estimatevalidation.domain.ValidationFlag;
import com.ssafy.a307.estimatevalidation.domain.ValidationStatus;
import com.ssafy.a307.estimatevalidation.dto.CostComparisonResponse;
import com.ssafy.a307.estimatevalidation.dto.ValidationAcceptedResponse;
import com.ssafy.a307.estimatevalidation.dto.ValidationHistoryResponse;
import com.ssafy.a307.estimatevalidation.dto.ValidationQuestionResponse;
import com.ssafy.a307.estimatevalidation.dto.ValidationStatusResponse;
import com.ssafy.a307.estimatevalidation.service.CostComparisonService;
import com.ssafy.a307.estimatevalidation.service.EstimateFileValidationService;
import com.ssafy.a307.estimatevalidation.service.EstimateValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({EstimateValidationController.class, CostComparisonController.class})
@AutoConfigureMockMvc(addFilters = false)
class EstimateValidationControllerTest {

    private static final long ME = 1L;

    @Autowired MockMvc mockMvc;
    @MockitoBean EstimateValidationService validationService;
    @MockitoBean EstimateFileValidationService fileValidationService;
    @MockitoBean CostComparisonService costComparisonService;
    @MockitoBean CurrentMemberProvider currentMemberProvider;

    @BeforeEach
    void setUp() {
        given(currentMemberProvider.currentMemberId()).willReturn(ME);
    }

    @Test
    void manualJsonUsesSameUrlAndReturns202Envelope() throws Exception {
        given(validationService.registerManual(eq(ME), any())).willReturn(
                new ValidationAcceptedResponse(10L, ValidationStatus.COMPLETED,
                        EstimateFileType.MANUAL, "/api/estimate-validations/10"));

        mockMvc.perform(post("/api/estimate-validations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accidentId": 7,
                                  "estimateId": 8,
                                  "fileType": "MANUAL",
                                  "items": [{
                                    "lineNo": 1,
                                    "rawItemName": "프론트 펜더",
                                    "workType": "판금",
                                    "quantity": 1,
                                    "partCost": 100000,
                                    "laborCost": 50000
                                  }]
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.validationId").value(10))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.inputType").value("MANUAL"));
    }

    @Test
    void invalidManualLineReturns400WithoutCallingService() throws Exception {
        mockMvc.perform(post("/api/estimate-validations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accidentId":7,"fileType":"MANUAL","items":[{
                                  "lineNo":1,"rawItemName":"범퍼","workType":"교환",
                                  "quantity":0,"partCost":1,"laborCost":0
                                }]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        then(validationService).should(never()).registerManual(any(), any());
    }

    @Test
    void multipartMetadataAndFileReturn202() throws Exception {
        var metadata = new MockMultipartFile(
                "metadata", "metadata.json", MediaType.APPLICATION_JSON_VALUE,
                "{\"accidentId\":7}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var file = new MockMultipartFile(
                "file", "estimate.pdf", MediaType.APPLICATION_PDF_VALUE,
                new byte[]{'%', 'P', 'D', 'F', '-'});
        given(fileValidationService.registerFile(eq(ME), any(), any())).willReturn(
                new ValidationAcceptedResponse(11L, ValidationStatus.QUEUED,
                        EstimateFileType.PDF, "/api/estimate-validations/11"));

        mockMvc.perform(multipart("/api/estimate-validations").file(metadata).file(file))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.inputType").value("PDF"));
    }

    @Test
    void multipartWithoutFileReturns400() throws Exception {
        var metadata = new MockMultipartFile(
                "metadata", "metadata.json", MediaType.APPLICATION_JSON_VALUE,
                "{\"accidentId\":7}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/estimate-validations").file(metadata))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        then(fileValidationService).should(never()).registerFile(any(), any(), any());
    }

    @Test
    void statusQuestionsHistoryDeleteAndPdfContracts() throws Exception {
        Instant now = Instant.parse("2026-09-06T00:00:00Z");
        given(validationService.status(ME, 10L)).willReturn(
                new ValidationStatusResponse(10L, EstimateFileType.MANUAL,
                        ValidationStatus.COMPLETED, null, now, now));
        given(validationService.questions(ME, 10L)).willReturn(List.of(
                new ValidationQuestionResponse(1L, 2L, (short) 1,
                        ValidationFlag.OVER_P75, "산정 근거를 알려주실 수 있나요?", (short) 1)));
        given(validationService.history(ME, 0, 20)).willReturn(
                new ValidationHistoryResponse(List.of(), 0, 20, 0, 0));
        given(fileValidationService.pdfDownload(ME, 10L))
                .willReturn(URI.create("https://example.invalid/report.pdf?signature=test"));

        mockMvc.perform(get("/api/estimate-validations/10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMPLETED"));
        mockMvc.perform(get("/api/estimate-validations/10/questions"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].sourceFlag").value("OVER_P75"));
        mockMvc.perform(get("/api/estimate-validations/me"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isArray());
        mockMvc.perform(get("/api/estimate-validations/10/pdf"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.invalid/report.pdf?signature=test"));
        mockMvc.perform(delete("/api/estimate-validations/10"))
                .andExpect(status().isNoContent());
    }

    @Test
    void costComparisonEndpointUsesOwnerContext() throws Exception {
        given(costComparisonService.compare(ME, 7L)).willReturn(
                new CostComparisonResponse(7L, null, List.of(),
                        new CostComparisonResponse.ActualRepair(null, null, null, null)));

        mockMvc.perform(get("/api/accidents/7/cost-comparison"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accidentId").value(7))
                .andExpect(jsonPath("$.data.actualRepair.cost").isEmpty());
    }
}
