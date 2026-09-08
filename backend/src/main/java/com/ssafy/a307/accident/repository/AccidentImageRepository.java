package com.ssafy.a307.accident.repository;

import com.ssafy.a307.accident.entity.AccidentImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 소유권은 <b>전부 쿼리 조건</b>으로 검증한다 — 조회한 뒤 자바에서 비교하지 않는다.
 * 경로는 {@code accident_image → accident → vehicle.member_id} 다
 * ({@code accident} 에 {@code member_id} 가 없는 것은 정본의 의도다).
 * <p>
 * 남의 사고·없는 사고·소유가 아닌 이미지는 모두 결과가 비어 404 로 이어진다. 403 을 주면
 * 그 자원이 존재한다는 사실이 새어 나간다.
 */
public interface AccidentImageRepository extends JpaRepository<AccidentImage, Long> {

    @Query("""
            select ai from AccidentImage ai
            join ai.accident a
            join a.vehicle v
            where ai.imageId = :imageId
              and a.accidentId = :accidentId
              and v.memberId = :memberId
            """)
    Optional<AccidentImage> findOwned(@Param("accidentId") Long accidentId,
                                     @Param("imageId") Long imageId,
                                     @Param("memberId") Long memberId);

    /**
     * 상태 조회용. asset 을 함께 가져온다 — 이미지마다 컬렉션을 따로 읽으면 20장이면 21번 조회다.
     * Hibernate 6 은 컬렉션 fetch join 의 중복 루트를 스스로 제거하므로 {@code distinct} 가 필요 없다.
     */
    @Query("""
            select ai from AccidentImage ai
            join ai.accident a
            join a.vehicle v
            left join fetch ai.assets
            where a.accidentId = :accidentId
              and v.memberId = :memberId
            order by ai.imageId asc
            """)
    List<AccidentImage> findAllOwned(@Param("accidentId") Long accidentId,
                                    @Param("memberId") Long memberId);

    @Query("""
            select ai from AccidentImage ai
            join ai.accident a
            join a.vehicle v
            where a.accidentId = :accidentId
              and v.memberId = :memberId
              and ai.imageId in :imageIds
            order by ai.imageId asc
            """)
    List<AccidentImage> findAllOwnedByIds(@Param("accidentId") Long accidentId,
                                         @Param("memberId") Long memberId,
                                         @Param("imageIds") Collection<Long> imageIds);

    /** 누적 장수 제약용. 완료되지 않은 예약 행도 자리를 차지한다. */
    @Query("select count(ai) from AccidentImage ai where ai.accident.accidentId = :accidentId")
    long countByAccidentId(@Param("accidentId") Long accidentId);
}
