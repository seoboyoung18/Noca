package com.ssafy.a307.estimatevalidation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "repair_cost_stat")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepairCostStatReadModel {
    @Id
    @Column(name = "stat_id")
    private Long statId;
    @Column(name = "car_class", nullable = false, length = 20)
    private String carClass;
    @Column(name = "part_code", nullable = false, length = 50)
    private String partCode;
    @Column(name = "damage_type", nullable = false, length = 20)
    private String damageType;
    @Column(name = "repair_method", nullable = false, length = 20)
    private String repairMethod;
    @Column(name = "case_count", nullable = false)
    private int caseCount;
    @Column(name = "cost_min", nullable = false)
    private int costMin;
    @Column(name = "cost_median", nullable = false)
    private int costMedian;
    @Column(name = "cost_p75", nullable = false)
    private int costP75;
    @Column(name = "cost_max", nullable = false)
    private int costMax;
}
