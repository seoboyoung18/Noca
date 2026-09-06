package com.ssafy.a307.estimatevalidation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "part_name_mapping")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PartNameMapping {
    @Id
    @Column(name = "raw_name", length = 200)
    private String rawName;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "part_code", nullable = false)
    private PartCode partCode;
}
