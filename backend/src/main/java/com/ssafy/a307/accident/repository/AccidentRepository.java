package com.ssafy.a307.accident.repository;

import com.ssafy.a307.accident.entity.Accident;
import com.ssafy.a307.accident.dto.AccidentResponse;
import com.ssafy.a307.accident.dto.AccidentVehicleSearchCondition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
            select new com.ssafy.a307.accident.dto.AccidentResponse(
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
    Page<AccidentResponse> findPageByMemberId(@Param("memberId") Long memberId, Pageable pageable);
}
