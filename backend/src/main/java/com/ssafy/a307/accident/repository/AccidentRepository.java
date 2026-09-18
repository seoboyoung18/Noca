package com.ssafy.a307.accident.repository;

import com.ssafy.a307.accident.entity.Accident;
import com.ssafy.a307.accident.dto.AccidentResponse;
import com.ssafy.a307.accident.dto.AccidentSummaryResponse;
import com.ssafy.a307.accident.dto.AccidentVehicleSearchCondition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AccidentRepository extends JpaRepository<Accident, Long> {

    /** Vehicle soft deletion does not hide its accident history from the owner. */
    @Query("""
            select a from Accident a
            join fetch a.vehicle v
            where a.accidentId = :accidentId and v.memberId = :memberId
            """)
    Optional<Accident> findByAccidentIdAndMemberId(@Param("accidentId") Long accidentId,
                                                   @Param("memberId") Long memberId);

    /**
     * 소유 여부만 본다. 행을 읽을 필요가 없는 호출자용이다 —
     * {@link #findByAccidentIdAndMemberId} 는 {@code join fetch} 로 차량까지 끌어온다.
     *
     * <p>없는 사고와 남의 사고가 <b>모두 false</b> 다. 호출자는 둘을 구분하지 말고 404 로 응답해야
     * 한다 — 403 은 그 사고가 존재한다는 사실을 알려 준다.
     */
    @Query("""
            select count(a) > 0 from Accident a
            join a.vehicle v
            where a.accidentId = :accidentId and v.memberId = :memberId
            """)
    boolean existsByAccidentIdAndMemberId(@Param("accidentId") Long accidentId,
                                          @Param("memberId") Long memberId);

    /** 검색 조건은 현재 vehicle/vehicle_model 값이 아닌 사고 스냅샷에서 투영한다. */
    @Query("""
            select new com.ssafy.a307.accident.dto.AccidentVehicleSearchCondition(
                a.accidentId,
                a.snapshotModelId,
                a.snapshotManufacturer,
                a.snapshotModelName,
                a.snapshotCarClass,
                cast(a.snapshotModelYear as integer)
            )
            from Accident a
            join a.vehicle v
            where a.accidentId = :accidentId and v.memberId = :memberId
            """)
    Optional<AccidentVehicleSearchCondition> findVehicleSearchCondition(
            @Param("accidentId") Long accidentId,
            @Param("memberId") Long memberId);

    /**
     * 폐차·매각(soft delete)된 차량의 사고도 이력에 남는다 — deleted_at 을 거르지 않는다.
     *
     * <p>{@code join fetch} 가 없으면 {@link com.ssafy.a307.accident.dto.AccidentResponse#from}
     * 이 LAZY 인 vehicle 을 건드릴 때 건수만큼 추가 쿼리가 나간다.
     */
    @Query("""
            select a from Accident a
            join fetch a.vehicle v
            where v.memberId = :memberId
            order by a.createdAt desc, a.accidentId desc
            """)
    List<Accident> findAllByMemberId(@Param("memberId") Long memberId);

    /**
     * 페이지 단위 이력 조회. 정렬·소유자 검사·soft delete 정책은 {@link #findAllByMemberId} 와 같다.
     *
     * <p><b>{@code join fetch} 를 쓰지 않고 생성자 투영을 쓴다.</b> {@code join fetch} 와
     * {@code Pageable} 을 함께 주면 Hibernate 가 전체 행을 메모리로 올린 뒤 자르므로
     * ({@code HHH90003004}) 페이징의 의미가 사라진다. 투영은 LAZY 연관을 아예 건드리지 않아
     * N+1 도 없고 필요한 열만 읽는다.
     *
     * <p>{@code countQuery} 를 따로 준 이유 — 기본 count 는 select 절을 그대로 감싸려 해
     * 생성자 투영과 맞지 않는다. 정렬은 count 에 불필요하므로 함께 뺐다.
     */
    @Query(value = """
            select new com.ssafy.a307.accident.dto.AccidentSummaryResponse(
                a.accidentId,
                v.vehicleId,
                a.vehicleInputType,
                a.snapshotModelId,
                a.snapshotManufacturer,
                a.snapshotModelName,
                a.snapshotVehicleType,
                a.snapshotCarClass,
                cast(a.snapshotModelYear as integer),
                a.createdAt
            )
            from Accident a
            join a.vehicle v
            where v.memberId = :memberId
            order by a.createdAt desc, a.accidentId desc
            """,
            countQuery = """
            select count(a)
            from Accident a
            join a.vehicle v
            where v.memberId = :memberId
            """)
    Page<AccidentSummaryResponse> findPageByMemberId(@Param("memberId") Long memberId, Pageable pageable);

    /**
     * 프로필의 사고 접수 건수. {@link #findAllByMemberId} 와 같은 기준이라
     * <b>폐차·매각된 차량의 사고도 센다</b> — 목록에는 보이는데 숫자에서 빠지면 안 된다.
     * <p>
     * {@code accident} 에는 {@code member_id} 가 없어 {@code vehicle} 을 거쳐 소유자를 찾는다.
     */
    @Query("""
            select count(a) from Accident a
            join a.vehicle v
            where v.memberId = :memberId
            """)
    long countByMemberId(@Param("memberId") Long memberId);

    /**
     * 사고별 이미지 장수(Task 225). <b>페이지의 id 로만 한 번 조회한다</b> — 건마다 세면
     * 20건 페이지에 20번이 더 나간다.
     *
     * <p>완료 통보를 못 받은 것도 센다. 화면의 "N장" 은 사용자가 올린 수이지 서버 처리 상태가 아니다.
     */
    @Query("""
            select ai.accident.accidentId as accidentId, count(ai) as imageCount
            from AccidentImage ai
            where ai.accident.accidentId in :accidentIds
            group by ai.accident.accidentId
            """)
    List<AccidentImageCountView> countImagesByAccidentIds(
            @Param("accidentIds") Collection<Long> accidentIds);

    /**
     * 사고별 대표 썸네일 키(Task 225). <b>가장 먼저 올린 이미지</b>의 {@code THUMBNAIL} 이다 —
     * 촬영 가이드가 전면부터 찍게 하므로 첫 장이 대표로 가장 자연스럽다.
     *
     * <p>{@code ROW_NUMBER()} 로 사고별 한 행만 남긴다. 조인만 하면 이미지 수만큼 행이 불어나
     * 사고 한 건이 여러 번 나온다.
     *
     * <p>전처리를 마치지 못한 이미지는 asset 이 없어 자연히 빠진다 — 그 사고는 썸네일이 {@code null} 이다.
     */
    @Query(value = """
            select accidentId, s3Key from (
                select ai.accident_id as accidentId,
                       aia.s3_key     as s3Key,
                       row_number() over (partition by ai.accident_id order by ai.image_id) as rn
                  from accident_image ai
                  join accident_image_asset aia
                    on aia.image_id = ai.image_id and aia.variant = 'THUMBNAIL'
                 where ai.accident_id in (:accidentIds)
            ) t where rn = 1
            """, nativeQuery = true)
    List<AccidentThumbnailView> findThumbnailKeysByAccidentIds(
            @Param("accidentIds") Collection<Long> accidentIds);

    /**
     * 사고별 <b>최신 분석 작업 상태 + 최신 견적</b>(Task 225 · prompt58).
     *
     * <h2>왜 한 쿼리인가</h2>
     *
     * <p>상태 배지에 분석 중·분석 실패를 넣으려면 {@code analysis_job.status} 가 필요한데,
     * 사고마다 따로 조회하면 20건 페이지에 20번이 더 나간다(N+1). 견적 보완 쿼리가 이미
     * {@code analysis_job} 을 지나가므로 <b>거기에 합류시켜 쿼리 수를 그대로 4개로 유지</b>한다.
     *
     * <h2>왜 CTE 둘인가 — 한 행으로 합칠 수 없다</h2>
     *
     * <p>두 값이 <b>서로 다른 행을 고른다.</b> 상태는 <b>가장 최근 작업</b>의 것이고, 금액은
     * <b>작업을 가리지 않은 최신 산정 견적</b>의 것이다. 하나의 {@code row_number()} 로 묶으면
     * 재분석이 진행 중일 때(새 작업에 아직 견적이 없다) <b>이전 견적의 금액이 사라진다</b> —
     * 지금 화면에 보이던 금액이 재분석을 걸자마자 빈칸이 되는 회귀다.
     *
     * <p>{@code latest_job} 이 {@code latest_estimate} 의 상위집합이라 LEFT JOIN 으로 충분하다 —
     * {@code estimate.job_id} 가 {@code NOT NULL} FK 라 견적이 있으면 작업도 반드시 있다.
     *
     * <h2>정렬 기준</h2>
     *
     * <p>작업은 {@code created_at desc, job_id desc} — {@code AnalysisJobRepository
     * #findByAccidentIdAndMemberId} 와 <b>같은 정렬이어야 한다.</b> 다르면 목록 배지와 진행 상태
     * API 가 서로 다른 작업을 가리킨다.
     *
     * <p>견적은 {@code created_at desc, version desc} — {@code EstimateQueryRepository
     * #findHistoryByAccident} 와 같다. 한 사고에 분석 작업이 여러 번 있을 수 있어
     * {@code version} 만으로는 순서가 정해지지 않는다.
     *
     * <h2>{@code estimateId} 를 금액과 같은 행에서 뽑는다</h2>
     *
     * <p>화면이 이 id 로 리포트·PDF 를 연다. 금액과 다른 버전을 가리키면 <b>목록에 보이던 금액과
     * 열리는 리포트의 금액이 다르다.</b> 그래서 별도 조회로 최신 견적을 다시 찾지 않는다.
     *
     * <p><b>따라서 {@code is_estimable = false} 뿐인 사고는 {@code estimateId} 도 {@code null}
     * 이다.</b> 산정 불가 견적은 금액이 전부 {@code null} 이라 여기서 걸러지고(화면에 "예상 0원"
     * 으로 보이면 안 된다), 그 결과 화면 21 의 PDF 버튼도 꺼진다. 산정 불가 견적의 리포트를
     * 받게 할지는 기획 결정이므로 여기서 임의로 열지 않았다.
     *
     * <p><b>금액은 지금도 항상 비어 있다.</b> {@code estimate} 를 만드는 운영 코드가 없다
     * ({@code S15P21A307-50} 비용 산정). 계약을 먼저 열어 두어 그쪽이 끝나면 FE 수정 없이 채워진다.
     *
     * <h2>체크리스트 상태를 함께 준다 (S15P21A307-552)</h2>
     *
     * <p><b>{@code estimateId} 로 체크리스트 유무를 가늠할 수 없기 때문이다.</b> 그 값은 위에
     * 적은 대로 산정된 견적에만 붙는데, 체크리스트는 <b>산정 불가 견적에도 만들어진다</b>. 화면이
     * {@code estimateId} 로 체크리스트 목록을 거르다가 산정 불가 사고의 체크리스트를 통째로
     * 숨겼다 — 금액을 내지 못한 견적일수록 정비소에서 물어볼 것이 남아 체크리스트가 더 필요하다.
     *
     * <p>{@code repair_checklist} 는 {@code uk_rcl_accident} 로 사고당 한 건이라 CTE 없이
     * {@code LEFT JOIN} 이면 된다. <b>추가 쿼리가 늘지 않는다</b> — 목록 조회는 지금도 3개다.
     */
    @Query(value = """
            with latest_job as (
                select aj.accident_id as accident_id,
                       aj.status      as status,
                       row_number() over (
                           partition by aj.accident_id
                           order by aj.created_at desc, aj.job_id desc) as rn
                  from analysis_job aj
                 where aj.accident_id in (:accidentIds)
            ), latest_estimate as (
                select aj.accident_id as accident_id,
                       e.estimate_id  as estimate_id,
                       e.total_min    as total_min,
                       e.total_median as total_median,
                       e.total_max    as total_max,
                       row_number() over (
                           partition by aj.accident_id
                           order by e.created_at desc, e.version desc) as rn
                  from estimate e
                  join analysis_job aj on aj.job_id = e.job_id
                 where aj.accident_id in (:accidentIds)
                   and e.is_estimable = true
            )
            select j.accident_id  as accidentId,
                   j.status       as jobStatus,
                   e.estimate_id  as estimateId,
                   e.total_min    as totalMin,
                   e.total_median as totalMedian,
                   e.total_max    as totalMax,
                   rc.status      as checklistStatus
              from latest_job j
              left join latest_estimate e
                on e.accident_id = j.accident_id and e.rn = 1
              left join repair_checklist rc
                on rc.accident_id = j.accident_id
             where j.rn = 1
            """, nativeQuery = true)
    List<AccidentAnalysisView> findAnalysisByAccidentIds(
            @Param("accidentIds") Collection<Long> accidentIds);

    interface AccidentImageCountView {
        Long getAccidentId();

        long getImageCount();
    }

    interface AccidentThumbnailView {
        Long getAccidentId();

        String getS3Key();
    }

    /**
     * 사고 한 건의 분석·견적 보완값.
     *
     * <p>{@code jobStatus} 를 {@code String} 으로 받는다 — 네이티브 쿼리라 스프링이 열 값을
     * enum 으로 바꿔 주지 않는다. 변환은 {@code AccidentService} 가
     * {@code AnalysisJobStatus.valueOf} 로 한 곳에서 한다.
     */
    interface AccidentAnalysisView {
        Long getAccidentId();

        /** {@code QUEUED · PROCESSING · COMPLETED · FAILED}. 작업이 있으면 항상 채워진다. */
        String getJobStatus();

        /** 최신 산정 견적의 id. 산정 가능한 견적이 없으면 {@code null} 이다. */
        Long getEstimateId();

        Integer getTotalMin();

        Integer getTotalMedian();

        Integer getTotalMax();

        /**
         * 체크리스트 상태 {@code QUEUED · PROCESSING · COMPLETED · FAILED} (S15P21A307-552).
         * <b>만든 적이 없으면 {@code null}</b> 이다 — 화면은 그 사고를 체크리스트 목록에서 뺀다.
         */
        String getChecklistStatus();
    }
}
