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
     * 사고별 최신 견적의 총액 범위(Task 225). 정렬 기준은
     * {@code EstimateQueryRepository.findHistoryByAccident} 와 같다 — 한 사고에 분석 작업이
     * 여러 번 있을 수 있어 {@code version} 만으로는 순서가 정해지지 않는다.
     *
     * <p><b>지금은 결과가 항상 비어 있다.</b> {@code estimate} 를 만드는 운영 코드가 없다
     * ({@code S15P21A307-50} 비용 산정). 계약을 먼저 열어 두어 그쪽이 끝나면 FE 수정 없이
     * 값이 채워진다.
     *
     * <p>{@code is_estimable = false} 인 견적은 금액이 전부 {@code null} 이라 거른다 —
     * 화면에 "예상 0원" 으로 보이면 안 된다.
     */
    @Query(value = """
            select accidentId, totalMin, totalMedian, totalMax from (
                select aj.accident_id as accidentId,
                       e.total_min    as totalMin,
                       e.total_median as totalMedian,
                       e.total_max    as totalMax,
                       row_number() over (
                           partition by aj.accident_id
                           order by e.created_at desc, e.version desc) as rn
                  from estimate e
                  join analysis_job aj on aj.job_id = e.job_id
                 where aj.accident_id in (:accidentIds)
                   and e.is_estimable = true
            ) t where rn = 1
            """, nativeQuery = true)
    List<AccidentEstimateView> findLatestEstimatesByAccidentIds(
            @Param("accidentIds") Collection<Long> accidentIds);

    interface AccidentImageCountView {
        Long getAccidentId();

        long getImageCount();
    }

    interface AccidentThumbnailView {
        Long getAccidentId();

        String getS3Key();
    }

    interface AccidentEstimateView {
        Long getAccidentId();

        Integer getTotalMin();

        Integer getTotalMedian();

        Integer getTotalMax();
    }
}
