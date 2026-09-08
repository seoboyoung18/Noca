package com.ssafy.a307.accident.repository;

import com.ssafy.a307.accident.entity.Accident;
import com.ssafy.a307.accident.dto.AccidentVehicleSearchCondition;
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
}
