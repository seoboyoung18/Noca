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

    private PartNameMapping(String rawName, PartCode partCode) {
        this.rawName = rawName;
        this.partCode = partCode;
    }

    /**
     * 관리자 등록. {@code rawName} 은 PK 라 이 순간 이후 바뀌지 않는다 —
     * 원문을 바꾸는 것은 다른 별칭을 만드는 일이므로 삭제 후 재등록이다.
     */
    public static PartNameMapping of(String rawName, PartCode partCode) {
        String stripped = rawName == null ? null : rawName.strip();
        if (stripped == null || stripped.isEmpty()) {
            throw new IllegalArgumentException("rawName is required");
        }
        return new PartNameMapping(stripped, java.util.Objects.requireNonNull(partCode, "partCode"));
    }

    /** 매핑 대상만 바꾼다. 이 별칭이 어느 부품을 가리키는지가 유일하게 바뀔 수 있는 값이다. */
    public void changeTarget(PartCode partCode) {
        this.partCode = java.util.Objects.requireNonNull(partCode, "partCode");
    }
}
