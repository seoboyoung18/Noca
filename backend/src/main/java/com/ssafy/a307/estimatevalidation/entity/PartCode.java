package com.ssafy.a307.estimatevalidation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "part_code")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PartCode {
    @Id
    @Column(name = "part_code", length = 50)
    private String partCode;

    @Column(name = "name_ko", nullable = false, length = 50)
    private String nameKo;

    @Column(name = "layout_zone", nullable = false, length = 20)
    private String layoutZone;

    @Column(name = "display_order", nullable = false)
    private short displayOrder;

    @Column(name = "is_active", nullable = false)
    private boolean active;
}
