package com.ssafy.a307.accident.repository;

import com.ssafy.a307.accident.entity.Accident;
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
}
