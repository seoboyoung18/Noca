package com.ssafy.a307.review.repository;

import com.ssafy.a307.review.entity.AccidentReview;
import com.ssafy.a307.review.entity.AccidentReviewStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccidentReviewRepository extends JpaRepository<AccidentReview, Long> {

    /**
     * 적재 멱등성의 근거. <b>{@code uk_ar_accident} 라 있어도 한 행이다.</b>
     *
     * <p>{@code -350} 의 적재는 이 조회로 "이미 있으면 아무 일도 하지 않는다" 를 만든다.
     * 예외를 던지지 않는 것이 요구다 — 관리자가 같은 것을 두 번 보면 안 되지만, 사용자가 실제
     * 수리비를 두 번 고쳤다고 요청이 500 으로 끝나서도 안 된다.
     */
    boolean existsByAccident_AccidentId(Long accidentId);

    Optional<AccidentReview> findByAccident_AccidentId(Long accidentId);

    /**
     * 관리자 대기 목록 (S15P21A307-350).
     *
     * <p><b>{@code join fetch} 로 사고를 함께 읽는다.</b> 목록이 사고의 실제 수리비를 보여 줘야
     * 하는데 지연 로딩으로 두면 페이지 크기만큼 추가 질의가 나간다({@code AdminPageRequest.MAX_SIZE}
     * 가 200이다). {@code ToOne} 이라 페이지네이션이 메모리로 새지 않는다.
     *
     * <p>{@code status} 가 {@code null} 이면 전체다 — 관리자 목록 필터의 이 저장소 관례다
     * ({@code PartCodeRepository.searchForAdmin}).
     *
     * <p>정렬은 호출자가 {@code Pageable} 로 준다. 기본값이 {@code queuedAt} 오름차순이라
     * {@code ix_ar_queue (status, queued_at) WHERE status = 'PENDING'} 을 그대로 탄다.
     */
    @Query(value = """
            select r from AccidentReview r
            join fetch r.accident
            where (:status is null or r.status = :status)
            """,
            countQuery = """
            select count(r) from AccidentReview r
            where (:status is null or r.status = :status)
            """)
    Page<AccidentReview> search(@Param("status") AccidentReviewStatus status, Pageable pageable);
}
