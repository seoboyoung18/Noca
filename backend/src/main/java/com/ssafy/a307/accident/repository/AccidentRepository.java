package com.ssafy.a307.accident.repository;

import com.ssafy.a307.accident.entity.Accident;
import com.ssafy.a307.accident.dto.AccidentVehicleSearchCondition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
