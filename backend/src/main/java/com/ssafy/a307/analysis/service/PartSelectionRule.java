package com.ssafy.a307.analysis.service;

import com.ssafy.a307.analysis.callback.AnalysisCallbackService;
import com.ssafy.a307.analysis.entity.AnalysisImageResult;
import com.ssafy.a307.analysis.entity.AnalysisJob;
import com.ssafy.a307.analysis.repository.AnalysisImageResultRepository;
import com.ssafy.a307.estimate.repository.EstimateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 사용자가 부위를 직접 골라 이어서 분석할 수 있는가 (S15P21A307-568).
 *
 * <h2>대상은 "파손은 찾았는데 부품을 못 찾은" 분석뿐이다</h2>
 *
 * <p>사진이 부품이 안 보일 만큼 가깝게 찍혔으면 부품 모델이 아무것도 못 찾고, 분석은 거기서
 * 끝난다. 사용자가 할 수 있는 일은 다시 찍는 것뿐이었다. 손상은 이미 찾았으니 부위만 알려 주면
 * 이어서 분석할 수 있다 — 이 규칙은 그 경우를 가려낸다.
 * <ul>
 *   <li>A. 사진이 전부 제외됐다({@code ALL_IMAGES_EXCLUDED}) — 부품이 0개면 AI 가 사진을 차량이
 *       아닌 것으로 걸러 낸다</li>
 *   <li>B. 산정 불가인데 사유가 {@code PART_NOT_RESOLVED} — 부품은 보이는데 손상과 짝짓지 못했다</li>
 * </ul>
 * 두 경우 모두 <b>부품이 빈 손상 검출이 하나라도 있어야</b> 한다. 손상조차 못 찾은 사진은
 * 차가 아니거나 손상이 안 보이는 것이라 지금처럼 다시 찍게 둔다. 사례가 모자라 산정하지
 * 못한 경우({@code INSUFFICIENT_CASES})는 대상이 아니다 — 부위를 골라도 사례는 늘지 않는다.
 *
 * <h2>AI 를 바꾸지 않고 가려낸다</h2>
 *
 * <p>AI 는 부품 모델과 손상 모델을 둘 다 항상 돌려서, 사진을 제외할 때도 손상 검출은
 * {@code detections[]} 에 담아 보낸다. 백엔드는 그 원문을 {@code analysis_image_result} 에
 * 제외된 사진까지 저장한다. 그래서 여기서는 저장된 원문만 읽는다.
 *
 * <p><b>재시도와 횟수를 함께 쓴다.</b> 부위를 골라 다시 하는 것도 새 작업이라 재시도 한도
 * ({@link AnalysisJob#MAX_RETRY_COUNT})를 넘으면 열지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PartSelectionRule {

    /** AI 가 부품을 하나도 확정하지 못해 산정하지 못했다는 사유. 계약의 {@code nonEstimableReason} 값이다. */
    static final String PART_NOT_RESOLVED = "PART_NOT_RESOLVED";

    private final AnalysisImageResultRepository imageResultRepository;
    private final EstimateRepository estimateRepository;
    private final ObjectMapper objectMapper;

    /**
     * <b>끝난 작업만 판단한다.</b> 분석 중에는 화면이 진행 상태를 계속 물어보는데, 그때마다 이미지
     * 결과를 읽을 이유가 없다 — 상태부터 보고 끝나지 않았으면 조회 없이 {@code false} 다.
     */
    public boolean isAvailable(AnalysisJob job) {
        if (!endedWithoutPart(job) || job.getRetryCount() >= AnalysisJob.MAX_RETRY_COUNT) {
            return false;
        }
        return imageResultRepository.findByJobId(job.getJobId()).stream()
                .map(AnalysisImageResult::getDetections)
                .anyMatch(this::hasDamageWithoutPart);
    }

    private boolean endedWithoutPart(AnalysisJob job) {
        return switch (job.getStatus()) {
            case FAILED -> AnalysisCallbackService.ALL_IMAGES_EXCLUDED.equals(job.getFailureReason());
            case COMPLETED -> estimateRepository.findFirstByJobIdOrderByVersionDesc(job.getJobId())
                    .filter(estimate -> !estimate.isEstimable())
                    .map(estimate -> PART_NOT_RESOLVED.equals(estimate.getNonEstimableReason()))
                    .orElse(false);
            default -> false;
        };
    }

    /**
     * 부품이 비어 있는 손상 검출이 있는가. 검출은 손상 단위라, 부품과 짝이 안 되면 {@code partCode}
     * 가 {@code null} 이다. <b>원문을 못 읽으면 {@code false}</b> — 모르면 열지 않는다. 열어 놓고
     * 재분석이 헛돌면 사용자는 횟수만 잃는다.
     */
    boolean hasDamageWithoutPart(String detections) {
        if (detections == null || detections.isBlank()) {
            return false;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(detections);
        } catch (RuntimeException e) {
            log.warn("검출 원문을 읽지 못해 부위 선택을 열지 않는다: {}", e.getClass().getSimpleName());
            return false;
        }
        if (!root.isArray()) {
            return false;
        }
        for (JsonNode detection : root) {
            JsonNode partCode = detection.path("partCode");
            if (!partCode.isString() || partCode.asString().isBlank()) {
                return true;
            }
        }
        return false;
    }
}
