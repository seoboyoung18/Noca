package com.ssafy.a307.vehicle.repository;

import com.ssafy.a307.vehicle.entity.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    /**
     * 내 차량 목록. {@code vehicle_model} 을 join fetch 로 함께 읽어 N+1 을 없앤다.
     * {@code deleted_at IS NULL} 조건이 부분 인덱스 {@code ix_vehicle_member} 와 맞물린다.
     */
    @Query("""
            select v from Vehicle v
            join fetch v.model
            where v.memberId = :memberId and v.deletedAt is null
            order by v.createdAt desc
            """)
    List<Vehicle> findAllActiveByMemberId(@Param("memberId") Long memberId);

    /** 수정용. 소유자 검사를 쿼리 조건에 넣어 남의 차량이 애초에 조회되지 않게 한다. */
    @Query("""
            select v from Vehicle v
            join fetch v.model
            where v.vehicleId = :vehicleId and v.memberId = :memberId and v.deletedAt is null
            """)
    Optional<Vehicle> findActiveByVehicleIdAndMemberId(@Param("vehicleId") Long vehicleId,
                                                       @Param("memberId") Long memberId);

    /** 삭제용. 이미 삭제된 차량도 찾아야 재삭제가 멱등하게 204 가 된다. */
    Optional<Vehicle> findByVehicleIdAndMemberId(Long vehicleId, Long memberId);
}
