package com.ssafy.a307.review.service;

import com.ssafy.a307.accident.entity.Accident;
import com.ssafy.a307.review.entity.AccidentReview;
import com.ssafy.a307.review.repository.AccidentReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 검수 대기 큐 적재 (S15P21A307-350).
 *
 * <h2>언제 넣는가 — 실제 수리비가 입력될 때다</h2>
 *
 * <p>후보는 둘이었다(prompt72 §2-1). <b>분석 완료 시가 아니라 실제 수리비 입력 시</b>를 골랐다.
 *
 * <ul>
 *   <li>재학습 데이터셋에 실릴 수 있는 건이 <b>{@code actual_repair_cost IS NOT NULL} 인 사고뿐</b>
 *       이다. {@code pipeline/jobs/load_service_accidents.py} 의 적재 조건이 그 한 줄이고,
 *       그것이 이 검수의 대상 범위를 정한다(answer71 §2-1)</li>
 *   <li>{@code snapshot_actual_repair_cost} 는 <b>승인 시점에 값이 있어야</b> 뜻이 있다. 분석
 *       완료 시점에 넣으면 그 값이 없는 건이 큐에 쌓이고, 관리자는 승인할 수 없는 것을 본다</li>
 *   <li>분석 완료는 <b>사고당 여러 번</b> 일어난다(재분석). 검수 단위는 사고 하나이므로
 *       ({@code uk_ar_accident}) 그 전이를 큐의 기준으로 삼으면 "몇 번째 분석의 검수인가" 를
 *       매번 정해야 한다 — 그 질문에는 {@code reviewed_job_id} 가 판정 시점에 답한다</li>
 * </ul>
 *
 * <p><b>분석 완료 시점에도 넣는 "두 훅" 안을 쓰지 않았다.</b> 멱등하게 만들 수는 있지만
 * ({@code uk_ar_accident}), 큐에 승인 불가능한 건이 섞이는 문제가 그대로 남는다. 넣는 자리를
 * 늘리는 것이 아니라 <b>넣을 자격을 정하는 것</b>이 이 결정의 핵심이다.
 *
 * <h2>멱등하다</h2>
 *
 * <p>사용자는 실제 수리비를 여러 번 고칠 수 있다. 두 번째부터는 <b>아무 일도 일어나지 않는다</b> —
 * 예외가 아니다. 관리자가 같은 사고를 두 번 보면 안 되지만, 그렇다고 사용자의 정정 요청이
 * 500 으로 끝나서도 안 된다.
 *
 * <p>존재 확인 뒤 저장이라 이론적으로는 경합이 있다. 그때는 {@code uk_ar_accident} 가 마지막
 * 방어선이라 <b>행은 여전히 하나</b>이고, 동시에 들어온 두 요청 중 하나가 실패한다. 한 사용자가
 * 자기 사고의 수리비를 동시에 두 번 보내는 경우라 지금 규모에서 막을 값이 아니다 —
 * {@code RepairChecklistRepository.claimQueued} 가 H2 호환을 위해 같은 형태를 택했다.
 *
 * <p><b>호출자의 트랜잭션에 참여한다.</b> 수리비 저장과 큐 적재가 함께 커밋돼야 한다 — 따로
 * 돌면 수리비는 들어갔는데 검수 대상이 되지 않은 사고가 조용히 생긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccidentReviewQueueService {

    private final AccidentReviewRepository reviewRepository;

    /**
     * 사고를 검수 대기로 올린다. <b>이미 있으면 아무 일도 하지 않는다.</b>
     *
     * <p>이미 판정된 사고도 다시 올리지 않는다. 승인된 뒤 금액이 바뀌면 재검수가 필요하지만,
     * 그것을 자동으로 {@code PENDING} 으로 되돌릴지는 정해지지 않았다(answer71 §7-3) —
     * 정해지지 않은 것을 코드가 먼저 정하지 않는다. 대신 그 대조 쿼리가 마이그레이션 파일
     * 꼬리에 있다.
     *
     * @return 새로 넣었으면 true
     */
    @Transactional
    public boolean enqueue(Accident accident, Instant now) {
        Long accidentId = accident.getAccidentId();
        if (reviewRepository.existsByAccident_AccidentId(accidentId)) {
            log.debug("이미 검수 큐에 있는 사고다: accidentId={}", accidentId);
            return false;
        }
        reviewRepository.save(AccidentReview.pending(accident, now));
        log.info("검수 대기 큐에 넣었다: accidentId={}", accidentId);
        return true;
    }
}
