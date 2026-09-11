package com.ssafy.a307.analysis.service;

import com.ssafy.a307.analysis.contract.AnalysisDocument;
import com.ssafy.a307.analysis.contract.Detection;
import com.ssafy.a307.analysis.domain.AnalysisDamageType;
import com.ssafy.a307.analysis.domain.AnalysisRepairMethod;
import com.ssafy.a307.analysis.entity.AnalysisJob;
import com.ssafy.a307.analysis.entity.DamagedPart;
import com.ssafy.a307.analysis.repository.AnalysisJobRepository;
import com.ssafy.a307.analysis.repository.DamagedPartRepository;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.estimatevalidation.entity.PartCode;
import com.ssafy.a307.estimatevalidation.repository.PartCodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 표준화 분석 결과(JSON)를 서비스 DB 에 적재한다 — {@code S15P21A307-218} 의 "분석 결과 JSON" 부분.
 *
 * <h2>이 클래스가 정하는 것과 정하지 않는 것</h2>
 *
 * <p>적재는 계약이 준 값을 <b>그대로</b> 옮기는 일이어야 한다. 그런데 계약과 DDL 이 어긋나는
 * 자리가 있어, 그 자리마다 "근거가 있으면 넣고 없으면 미룬다" 로 통일했다.
 * <b>근거 없는 기본값을 만들지 않는다</b> — 이 값들은 견적까지 흘러가고, 한 번 들어가면
 * 나중에 무엇이 추정치였는지 구분할 수 없다.
 *
 * <ul>
 *   <li><b>수리 방식</b> — 후보가 하나일 때만 적재. 둘 이상이면 미룬다
 *       ({@link DeferralReason#AMBIGUOUS_WORK_CANDIDATE})</li>
 *   <li><b>신뢰도</b> — {@code part}·{@code damage} 중 작은 값. 둘 다 없으면 미룬다</li>
 *   <li><b>심각도</b> — 계약에 없다. 비운다</li>
 *   <li><b>좌표</b> — 저장할 컬럼이 없다. 검증만 하고 버린다</li>
 *   <li><b>같은 부품 중복</b> — 값이 같으면 한 행, 다르면 <b>양쪽 다</b> 미룬다</li>
 * </ul>
 *
 * <h2>작업 상태를 바꾸지 않는다</h2>
 *
 * <p>{@code analysis_job.status} 를 {@code COMPLETED} 로 옮기지 않는다. 작업의 수명은 비동기 분석
 * 요청 스토리({@code S15P21A307-155}, 서보영 담당)가 관리하며, 적재가 상태까지 옮기면 한 작업의
 * 상태를 두 곳이 바꾸게 된다. {@code model_version} 도 채우지 않는다 —
 * <b>표준화 계약에 모델 버전 필드가 없다.</b>
 */
@Service
@RequiredArgsConstructor
public class AnalysisResultIngestService {

    private final AnalysisJobRepository analysisJobRepository;
    private final DamagedPartRepository damagedPartRepository;
    private final PartCodeRepository partCodeRepository;

    /**
     * 문서 하나(= 이미지 한 장)를 적재한다.
     *
     * <p>소유자 검사는 쿼리 조건에 있다. <b>없는 작업과 남의 작업이 모두 404</b> 다.
     *
     * @param memberId 세션에서 얻은 회원. 요청 본문에서 받지 않는다
     * @param jobId    이미 만들어져 있는 분석 작업
     * @param document 계약을 이미 통과한 문서 ({@code AnalysisDocumentReader} 가 검증한다)
     */
    @Transactional
    public AnalysisIngestResult ingest(Long memberId, Long jobId, AnalysisDocument document) {
        if (document == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "분석 결과 문서가 비어 있습니다.");
        }
        AnalysisJob job = analysisJobRepository.findByJobIdAndMemberId(jobId, memberId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND, "분석 작업을 찾을 수 없습니다."));

        List<Detection> detections = document.detections();
        List<AnalysisIngestResult.Deferred> deferred = new ArrayList<>();

        // 같은 문서 안에서 같은 부품이 두 번 나오는 경우를 먼저 가른다.
        // 값이 어긋나는 부품은 양쪽 다 빼야 하므로, 한 건씩 저장하며 판단할 수 없다.
        Map<String, Resolved> byPartCode = new LinkedHashMap<>();
        Map<String, DeferralReason> rejected = new HashMap<>();

        for (Detection detection : detections) {
            String partCode = detection.part().code();

            Optional<DeferralReason> blocked = blockingReason(detection, partCode);
            if (blocked.isPresent()) {
                deferred.add(new AnalysisIngestResult.Deferred(partCode, blocked.get()));
                continue;
            }

            Resolved candidate = resolve(detection);
            if (rejected.containsKey(partCode)) {
                // 이미 충돌로 뺀 부품이다. 같은 이유로 이 건도 뺀다.
                deferred.add(new AnalysisIngestResult.Deferred(
                        partCode, DeferralReason.CONFLICTING_DUPLICATE_PART));
                continue;
            }

            Resolved seen = byPartCode.get(partCode);
            if (seen == null) {
                byPartCode.put(partCode, candidate);
                continue;
            }
            if (seen.sameDecisionAs(candidate)) {
                // 같은 부품·같은 판정이 두 번 왔다. uk_dp 상 한 행이면 충분하다.
                continue;
            }
            // 서로 다른 판정이다. 순서가 결과를 바꾸지 않도록 양쪽 다 뺀다.
            byPartCode.remove(partCode);
            rejected.put(partCode, DeferralReason.CONFLICTING_DUPLICATE_PART);
            deferred.add(new AnalysisIngestResult.Deferred(
                    partCode, DeferralReason.CONFLICTING_DUPLICATE_PART));
            deferred.add(new AnalysisIngestResult.Deferred(
                    partCode, DeferralReason.CONFLICTING_DUPLICATE_PART));
        }

        int persisted = 0;
        for (Map.Entry<String, Resolved> entry : byPartCode.entrySet()) {
            String partCode = entry.getKey();
            // 다른 이미지의 문서가 같은 부품을 이미 넣었을 수 있다. uk_dp 위반을 만들지 않는다.
            if (damagedPartRepository.existsByJob_JobIdAndPartCode(job.getJobId(), partCode)) {
                deferred.add(new AnalysisIngestResult.Deferred(
                        partCode, DeferralReason.CONFLICTING_DUPLICATE_PART));
                continue;
            }
            Resolved resolved = entry.getValue();
            damagedPartRepository.save(DamagedPart.detected(
                    job, partCode, resolved.damageType(), resolved.repairMethod(), resolved.confidence()));
            persisted++;
        }

        return new AnalysisIngestResult(job.getJobId(), detections.size(), persisted, deferred);
    }

    /** 적재를 막는 사유가 있으면 돌려준다. 없으면 비어 있다. */
    private Optional<DeferralReason> blockingReason(Detection detection, String partCode) {
        if (!detection.hasSingleWorkCandidate()) {
            return Optional.of(DeferralReason.AMBIGUOUS_WORK_CANDIDATE);
        }
        if (detection.confidence().effective().isEmpty()) {
            return Optional.of(DeferralReason.MISSING_CONFIDENCE);
        }
        Optional<PartCode> master = partCodeRepository.findById(partCode);
        if (master.isEmpty()) {
            return Optional.of(DeferralReason.UNKNOWN_PART_CODE);
        }
        if (!master.get().isActive()) {
            return Optional.of(DeferralReason.INACTIVE_PART_CODE);
        }
        return Optional.empty();
    }

    private Resolved resolve(Detection detection) {
        return new Resolved(
                AnalysisDamageType.from(detection.damage().code()),
                AnalysisRepairMethod.from(detection.workCandidates().getFirst().code()),
                detection.confidence().effective().orElseThrow());
    }

    /** 한 검출에서 뽑아낸, 그대로 저장할 수 있는 값. */
    private record Resolved(AnalysisDamageType damageType,
                            AnalysisRepairMethod repairMethod,
                            BigDecimal confidence) {

        /**
         * 같은 부품의 두 검출이 같은 판정인가.
         *
         * <p><b>신뢰도는 비교하지 않는다.</b> 같은 손상·같은 수리 방식이면 이미지마다 신뢰도가
         * 조금 달라도 같은 사실을 가리킨다. 신뢰도까지 견주면 소수점 차이로 멀쩡한 검출이
         * 전부 충돌로 빠진다.
         */
        boolean sameDecisionAs(Resolved other) {
            return damageType == other.damageType && repairMethod == other.repairMethod;
        }
    }
}
