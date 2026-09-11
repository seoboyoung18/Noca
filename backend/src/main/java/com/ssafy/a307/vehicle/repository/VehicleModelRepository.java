package com.ssafy.a307.vehicle.repository;

import com.ssafy.a307.vehicle.entity.CarClass;
import com.ssafy.a307.vehicle.entity.VehicleModel;
import com.ssafy.a307.vehicle.entity.VehicleType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VehicleModelRepository extends JpaRepository<VehicleModel, Long> {

    List<VehicleModel> findByActiveTrueOrderByManufacturerAscModelNameAsc();

    Optional<VehicleModel> findByModelIdAndActiveTrue(Long modelId);

    Optional<VehicleModel> findByManufacturerAndModelNameAndActiveTrue(
            String manufacturer, String modelName);

    /**
     * 관리자 목록. <b>비활성 모델도 나온다</b> — 관리자는 꺼 둔 것을 다시 켜야 하므로 공개
     * 목록({@link #findByActiveTrueOrderByManufacturerAscModelNameAsc})과 필터가 다르다.
     * {@code active} 가 {@code null} 이면 전체다. {@code vehicleType}·{@code carClass} 도
     * {@code null} 이면 거르지 않는다.
     */
    @Query("""
            select m from VehicleModel m
            where (:active is null or m.active = :active)
              and (:manufacturer is null or lower(m.manufacturer) = lower(:manufacturer))
              and (:vehicleType is null or m.vehicleType = :vehicleType)
              and (:carClass is null or m.carClass = :carClass)
              and (:keyword is null
                   or lower(m.manufacturer) like lower(concat('%', :keyword, '%'))
                   or lower(m.modelName)   like lower(concat('%', :keyword, '%')))
            """)
    Page<VehicleModel> searchForAdmin(@Param("keyword") String keyword,
                                      @Param("manufacturer") String manufacturer,
                                      @Param("vehicleType") VehicleType vehicleType,
                                      @Param("carClass") CarClass carClass,
                                      @Param("active") Boolean active,
                                      Pageable pageable);

    /** 중복 등록 차단. 비활성 모델과도 겹치면 안 되므로 활성 조건을 넣지 않는다. */
    boolean existsByManufacturerAndModelName(String manufacturer, String modelName);
}
