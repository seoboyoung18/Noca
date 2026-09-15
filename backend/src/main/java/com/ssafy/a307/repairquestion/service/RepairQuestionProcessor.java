package com.ssafy.a307.repairquestion.service;

import com.ssafy.a307.accident.entity.Accident;
import com.ssafy.a307.analysis.entity.AnalysisJob;
import com.ssafy.a307.analysis.entity.AnalysisJobStatus;
import com.ssafy.a307.analysis.entity.DamagedPart;
import com.ssafy.a307.analysis.repository.AnalysisJobRepository;
import com.ssafy.a307.analysis.repository.DamagedPartRepository;
import com.ssafy.a307.estimate.entity.ConfidenceGrade;
import com.ssafy.a307.estimate.entity.Estimate;
import com.ssafy.a307.estimate.repository.EstimateRepository;
import com.ssafy.a307.estimatevalidation.entity.PartCode;
import com.ssafy.a307.estimatevalidation.repository.PartCodeRepository;
import com.ssafy.a307.repairquestion.domain.RepairQuestionFailure;
import com.ssafy.a307.repairquestion.domain.RepairQuestionGenerationException;
import com.ssafy.a307.repairquestion.entity.RepairQuestion;
import com.ssafy.a307.repairquestion.entity.RepairQuestionItem;
import com.ssafy.a307.repairquestion.entity.RepairQuestionStatus;
import com.ssafy.a307.repairquestion.repository.RepairQuestionItemRepository;
import com.ssafy.a307.repairquestion.repository.RepairQuestionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 질문 목록 한 건을 실제로 만든다. <b>트랜잭션 경계를 위해 워커에서 떼어낸 빈이다.</b>
 *
 * <p>워커 안에 두고 {@code this.claim(...)} 으로 부르면 프록시를 거치지 않아
 * {@code @Transactional} 이 먹지 않고 {@code @Modifying} 쿼리가 터진다
 * ({@code RepairChecklistProcessor} 가 같은 이유로 나뉘어 있다). 선점은 처리와도 분리돼야 한다 —
 * 처리 트랜잭션이 롤백돼도 선점은 남아야 같은 건을 무한히 다시 집지 않는다.
 *
 * <h2>근거는 여기서 확정한다</h2>
 *
 * <p>LLM 은 "이 질문이 어느 부품 이야기인가" 만 말한다({@link RepairQuestionDraft}).
 * {@code snapshot_part_name} 은 <b>지금 시점의 {@code part_code.name_ko} 사본</b>이고,
 * {@code damage_type} · {@code repair_method} 는 <b>{@code damaged_part} 의 값 복사</b>다.
 * {@code damaged_part} 를 FK 로 가리키지 않는 이유는 정본 DDL 12-2 절 주석에 있다 — 그 테이블은
 * {@code job_id} 에 매여 있고 {@code ON DELETE CASCADE} 라, 재분석하거나 job 이 지워지면 질문까지
 * 따라 사라진다.
 *
 * <h2>순서 (S15P21A307-476)</h2>
 *
 * <p>{@code display_order} 는 <b>LLM 이 준 순서 그대로 1..N</b> 이다. 체크리스트처럼 뒤에 붙일
 * 공통 항목이 없어 두 구간으로 나눌 일이 없고, "이 사고에만 해당하는 것부터" 라는 지시를 준
 * 이상 서버가 다시 정렬하면 그 판단을 덮어쓰는 셈이 된다. 번호는 이어지는 정수다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepairQuestionProcessor {

    private final RepairQuestionRepository questionRepository;
    private final RepairQuestionItemRepository itemRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final DamagedPartRepository damagedPartRepository;
    private final EstimateRepository estimateRepository;
    private final PartCodeRepository partCodeRepository;
    private final RepairQuestionGenerator generator;

    /**
     * {@code QUEUED} 인 건만 {@code PROCESSING} 으로 선점한다.
     *
     * @return 내가 선점했으면 true. 0행이면 다른 워커가 이미 가져갔다는 뜻이라 건너뛴다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(Long questionId) {
        return questionRepository.claimQueued(questionId) == 1;
    }

    /**
     * 선점한 건을 만든다.
     *
     * @throws RepairQuestionGenerationException 이 건만 실패. 워커가 받아 {@link #markFailed} 로 기록한다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(Long questionId) {
        RepairQuestion question = questionRepository.findById(questionId)
                .orElseThrow(() -> new RepairQuestionGenerationException(
                        RepairQuestionFailure.INTERNAL, "선점한 질문 목록이 사라졌다: " + questionId));

        List<RepairQuestionDraft> drafts = generator.generate(context(question.getAccident()));

        // 앞 시도가 남긴 질문을 먼저 비운다. 실패한 건을 다시 만들 때 두 시도가 섞이면
        // 사용자는 어느 줄이 최신인지 알 수 없다. 벌크 DELETE 라 이 줄에서 바로 나간다.
        int stale = itemRepository.deleteAllByQuestionId(questionId);
        if (stale > 0) {
            log.info("앞 시도가 남긴 질문을 지웠다: questionId={}, {}건", questionId, stale);
        }

        Instant now = Instant.now();
        List<RepairQuestionItem> items = new ArrayList<>();
        int order = 1;
        for (RepairQuestionDraft draft : drafts) {
            items.add(toItem(question, draft, order++, now));
        }

        itemRepository.saveAll(items);
        question.markCompleted(now);
        log.info("질문 목록 생성 완료: questionId={}, 질문={}건", questionId, items.size());
    }

    /**
     * 근거가 온전할 때만 근거 있는 항목으로 만든다.
     *
     * <p>{@code ck_rqi_basis} 는 {@code part_code} 와 {@code snapshot_part_name} 이 함께 있기를
     * 요구한다. 부품 마스터에서 한글명을 못 찾았다면 <b>근거를 통째로 비운다</b> — 코드만 남기면
     * INSERT 가 거부되고, 이름을 코드로 대신 채우면 화면에 영문 코드가 그대로 보인다.
     */
    private static RepairQuestionItem toItem(RepairQuestion question, RepairQuestionDraft draft,
                                             int order, Instant now) {
        RepairQuestionContext.DamagedPartView basis = draft.basis();
        if (basis == null || basis.partName() == null || basis.partName().isBlank()) {
            return RepairQuestionItem.general(question, draft.content(), order, now);
        }
        return RepairQuestionItem.grounded(question, draft.content(),
                basis.partCode(), basis.partName(), basis.damageType(), basis.repairMethod(),
                order, now);
    }

    /**
     * 실패를 기록한다. <b>{@link #process} 와 다른 트랜잭션이어야 한다</b> — 같은 트랜잭션이면
     * 처리 실패와 함께 롤백돼 사유가 남지 않는다.
     *
     * <p>이미 끝난 건은 건드리지 않는다. 워커 둘이 겹쳐 돌 때 한쪽이 완료한 건을 다른 쪽이
     * 실패로 덮는 일을 막는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long questionId, RepairQuestionFailure failure) {
        questionRepository.findById(questionId).ifPresent(question -> {
            if (question.getStatus() == RepairQuestionStatus.COMPLETED) {
                return;
            }
            question.markFailed(failure.name(), Instant.now());
        });
    }

    /**
     * 지시문에 실을 사고 정보를 모은다 — <b>분석 결과와 예상 견적</b>이다
     * ({@code S15P21A307-476} 본문).
     *
     * <p>손상 부위와 견적은 <b>가장 최근 {@code COMPLETED} 분석</b>에서 읽는다. 진행 중이거나
     * 실패한 작업의 부분 결과로 질문을 만들면, 분석이 끝난 뒤 내용이 달라져도 질문은 그대로 남는다.
     *
     * <p><b>분석이 없어도 만든다.</b> 사용자가 분석을 돌리기 전에 정비소부터 가는 흐름을 막을
     * 이유가 없고, 그때는 차량 정보만으로 일반적인 질문이 나온다. 없는 손상을 지어내지 말라는
     * 지시는 지시문에 들어 있다.
     */
    private RepairQuestionContext context(Accident accident) {
        Optional<Long> jobId = analysisJobRepository
                .findByAccident_AccidentIdOrderByCreatedAtDescJobIdDesc(accident.getAccidentId()).stream()
                .filter(job -> job.getStatus() == AnalysisJobStatus.COMPLETED)
                .findFirst()
                .map(AnalysisJob::getJobId);

        List<DamagedPart> damagedParts = jobId
                .map(damagedPartRepository::findByJob_JobIdOrderByPartCodeAsc)
                .orElseGet(List::of);

        Map<String, String> partNames = partNames(damagedParts);
        List<RepairQuestionContext.DamagedPartView> parts = damagedParts.stream()
                .map(part -> new RepairQuestionContext.DamagedPartView(
                        part.getPartCode(), partNames.get(part.getPartCode()),
                        part.getDamageType(), part.getRepairMethod()))
                .toList();

        return new RepairQuestionContext(
                accident.getSnapshotManufacturer(),
                accident.getSnapshotModelName(),
                accident.getSnapshotModelYear(),
                parts,
                jobId.flatMap(estimateRepository::findFirstByJobIdOrderByVersionDesc)
                        .map(RepairQuestionProcessor::toEstimateView)
                        .orElse(null));
    }

    /**
     * 부품 한글명을 <b>생성 시점에</b> 읽는다. 이 값이 그대로 {@code snapshot_part_name} 이 된다.
     *
     * <p>{@code findAllById} 한 번으로 끝낸다 — 부품 수만큼 조회를 반복하지 않는다.
     */
    private Map<String, String> partNames(List<DamagedPart> damagedParts) {
        List<String> codes = damagedParts.stream().map(DamagedPart::getPartCode).distinct().toList();
        if (codes.isEmpty()) {
            return Map.of();
        }
        return partCodeRepository.findAllById(codes).stream()
                .collect(Collectors.toMap(PartCode::getPartCode, PartCode::getNameKo,
                        (first, second) -> first, LinkedHashMap::new));
    }

    /** 금액을 담지 않는다 — 이유는 {@link RepairQuestionContext.EstimateView} 에 있다. */
    private static RepairQuestionContext.EstimateView toEstimateView(Estimate estimate) {
        ConfidenceGrade grade = estimate.getConfidenceGrade();
        return new RepairQuestionContext.EstimateView(
                estimate.isEstimable(), grade == null ? null : grade.name());
    }
}
