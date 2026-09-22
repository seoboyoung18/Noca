package com.ssafy.a307.analysis.service;

import com.ssafy.a307.analysis.callback.AnalysisCallbackService;
import com.ssafy.a307.analysis.entity.AnalysisImageResult;
import com.ssafy.a307.analysis.entity.AnalysisJob;
import com.ssafy.a307.analysis.entity.AnalysisJobStatus;
import com.ssafy.a307.analysis.repository.AnalysisImageResultRepository;
import com.ssafy.a307.estimate.entity.Estimate;
import com.ssafy.a307.estimate.repository.EstimateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 부위를 직접 골라 이어서 분석할 수 있는가 (S15P21A307-568).
 *
 * <p>대상은 "파손은 찾았는데 부품을 못 찾은" 분석뿐이다. 열지 말아야 할 경우를 함께 본다 —
 * 잘못 열면 사용자는 헛도는 재분석에 횟수만 잃는다.
 */
@DisplayName("부위 선택 판정")
class PartSelectionRuleTest {

    private static final long JOB_ID = 20L;

    /** 부품과 짝이 안 된 손상. 부품이 0개라 사진이 제외될 때 AI 가 이렇게 보낸다. */
    private static final String UNPAIRED = """
            [{"detectionId":"1:damage:001","partCode":null,"damageType":"Scratched","pairStatus":"UNPAIRED"}]""";

    private static final String PAIRED = """
            [{"detectionId":"1:damage:001","partCode":"FRONT_BUMPER","damageType":"Scratched","pairStatus":"PAIRED"}]""";

    private final AnalysisImageResultRepository imageResults = mock(AnalysisImageResultRepository.class);
    private final EstimateRepository estimates = mock(EstimateRepository.class);
    private final PartSelectionRule rule = new PartSelectionRule(imageResults, estimates, new ObjectMapper());

    @Test
    @DisplayName("A. 사진이 전부 제외됐는데 손상은 찾았으면 연다")
    void allExcludedWithDamageOpens() {
        AnalysisJob job = failed(AnalysisCallbackService.ALL_IMAGES_EXCLUDED, 0);
        detections(UNPAIRED);

        assertThat(rule.isAvailable(job)).isTrue();
    }

    /** 손상조차 못 찾았으면 차가 아니거나 손상이 안 보이는 것이다. 지금처럼 다시 찍게 둔다. */
    @Test
    @DisplayName("A. 사진이 전부 제외됐고 손상도 없으면 열지 않는다")
    void allExcludedWithoutDamageStaysClosed() {
        AnalysisJob job = failed(AnalysisCallbackService.ALL_IMAGES_EXCLUDED, 0);
        detections("[]");

        assertThat(rule.isAvailable(job)).isFalse();
    }

    @Test
    @DisplayName("B. 부품을 못 짝지어 산정하지 못했으면 연다")
    void partNotResolvedOpens() {
        AnalysisJob job = completed();
        nonEstimable(PartSelectionRule.PART_NOT_RESOLVED);
        detections(UNPAIRED);

        assertThat(rule.isAvailable(job)).isTrue();
    }

    /** 사례가 모자란 건 부위를 골라도 해결되지 않는다. */
    @Test
    @DisplayName("사례 부족으로 산정하지 못했으면 열지 않는다")
    void insufficientCasesStaysClosed() {
        AnalysisJob job = completed();
        nonEstimable("INSUFFICIENT_CASES");
        detections(UNPAIRED);

        assertThat(rule.isAvailable(job)).isFalse();
    }

    @Test
    @DisplayName("견적이 나왔으면 열지 않는다")
    void estimableStaysClosed() {
        AnalysisJob job = completed();
        Estimate estimate = mock(Estimate.class);
        given(estimate.isEstimable()).willReturn(true);
        given(estimates.findFirstByJobIdOrderByVersionDesc(JOB_ID)).willReturn(Optional.of(estimate));
        detections(UNPAIRED);

        assertThat(rule.isAvailable(job)).isFalse();
    }

    @Test
    @DisplayName("부품이 다 짝지어졌으면 열지 않는다")
    void pairedDamageStaysClosed() {
        AnalysisJob job = failed(AnalysisCallbackService.ALL_IMAGES_EXCLUDED, 0);
        detections(PAIRED);

        assertThat(rule.isAvailable(job)).isFalse();
    }

    /** 부위 선택도 새 작업이라 재시도 한도를 함께 쓴다. */
    @Test
    @DisplayName("재시도와 합친 횟수를 다 썼으면 열지 않는다")
    void exhaustedRetriesStayClosed() {
        AnalysisJob job = failed(AnalysisCallbackService.ALL_IMAGES_EXCLUDED, AnalysisJob.MAX_RETRY_COUNT);
        detections(UNPAIRED);

        assertThat(rule.isAvailable(job)).isFalse();
    }

    /**
     * 부위 확정 재분석(S15P21A307-570)은 "대상이 아님" 과 "횟수를 다 씀" 을 다른 문구로 알린다.
     * 그래서 대상 여부는 횟수와 따로 답해야 한다 — 화면이 보는 판정은 그대로 닫혀 있다.
     */
    @Test
    @DisplayName("횟수를 다 써도 대상인지는 따로 답한다 — 열지는 않는다")
    void eligibilityIgnoresRetryCount() {
        AnalysisJob job = failed(AnalysisCallbackService.ALL_IMAGES_EXCLUDED, AnalysisJob.MAX_RETRY_COUNT);
        detections(UNPAIRED);

        assertThat(rule.isEligible(job)).isTrue();
        assertThat(rule.isAvailable(job)).isFalse();
    }

    @Test
    @DisplayName("다른 이유로 실패했으면 열지 않는다")
    void otherFailureStaysClosed() {
        AnalysisJob job = failed("AI_TIMEOUT", 0);
        detections(UNPAIRED);

        assertThat(rule.isAvailable(job)).isFalse();
    }

    /** 분석 중엔 화면이 상태를 계속 물어본다. 그때마다 이미지 결과를 읽지 않는다. */
    @Test
    @DisplayName("분석 중이면 조회 없이 닫혀 있다")
    void inProgressDoesNotQuery() {
        AnalysisJob job = job(AnalysisJobStatus.PROCESSING, null, 0);

        assertThat(rule.isAvailable(job)).isFalse();
        verify(imageResults, never()).findByJobId(anyLong());
        verify(estimates, never()).findFirstByJobIdOrderByVersionDesc(anyLong());
    }

    /** 모르면 열지 않는다 — 열어 놓고 재분석이 헛돌면 사용자는 횟수만 잃는다. */
    @Test
    @DisplayName("검출 원문을 못 읽으면 열지 않는다")
    void brokenDetectionsStayClosed() {
        assertThat(rule.hasDamageWithoutPart("{not json")).isFalse();
        assertThat(rule.hasDamageWithoutPart(null)).isFalse();
        assertThat(rule.hasDamageWithoutPart("{\"detections\":[]}")).isFalse();
    }

    private AnalysisJob failed(String reason, int retryCount) {
        return job(AnalysisJobStatus.FAILED, reason, retryCount);
    }

    private AnalysisJob completed() {
        return job(AnalysisJobStatus.COMPLETED, null, 0);
    }

    private static AnalysisJob job(AnalysisJobStatus status, String failureReason, int retryCount) {
        AnalysisJob job = mock(AnalysisJob.class);
        given(job.getJobId()).willReturn(JOB_ID);
        given(job.getStatus()).willReturn(status);
        given(job.getFailureReason()).willReturn(failureReason);
        given(job.getRetryCount()).willReturn((short) retryCount);
        return job;
    }

    private void nonEstimable(String reason) {
        Estimate estimate = mock(Estimate.class);
        given(estimate.isEstimable()).willReturn(false);
        given(estimate.getNonEstimableReason()).willReturn(reason);
        given(estimates.findFirstByJobIdOrderByVersionDesc(JOB_ID)).willReturn(Optional.of(estimate));
    }

    /** 목을 먼저 만든다 — given(...) 안에서 다른 목을 stub 하면 UnfinishedStubbingException 이다. */
    private void detections(String json) {
        AnalysisImageResult result = mock(AnalysisImageResult.class);
        given(result.getDetections()).willReturn(json);
        given(imageResults.findByJobId(JOB_ID)).willReturn(List.of(result));
    }
}
