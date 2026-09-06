package com.ssafy.a307.estimatevalidation.repository;

import com.ssafy.a307.estimatevalidation.entity.RepairCostStatReadModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RepairCostStatRepository extends JpaRepository<RepairCostStatReadModel, Long> {
    Optional<RepairCostStatReadModel>
    findFirstByCarClassAndPartCodeAndDamageTypeAndRepairMethodOrderByCaseCountDescStatIdAsc(
            String carClass, String partCode, String damageType, String repairMethod);
}
