package com.ssafy.a307.estimatevalidation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "estimate")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EstimateReadModel {
    @Id
    @Column(name = "estimate_id")
    private Long estimateId;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(name = "version", nullable = false)
    private short version;

    @Column(name = "total_min")
    private Integer totalMin;

    @Column(name = "total_median")
    private Integer totalMedian;

    @Column(name = "total_max")
    private Integer totalMax;
}
