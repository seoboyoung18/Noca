package com.ssafy.a307.vehicle.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * 회원이 보유한 차량.
 * <p>
 * member 는 다른 담당자의 도메인이므로 연관관계를 걸지 않고 {@code member_id} 를 값으로만 가진다.
 * 시각 컬럼이 전부 {@code TIMESTAMPTZ} 라 {@link Instant} 로 매핑한다.
 */
@Entity
@Table(name = "vehicle")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "vehicle_id")
    private Long vehicleId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private VehicleModel model;

    @Column(name = "model_year", nullable = false)
    private Short modelYear;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    private Vehicle(Long memberId, VehicleModel model, Short modelYear) {
        this.memberId = memberId;
        this.model = model;
        this.modelYear = modelYear;
    }

    public static Vehicle register(Long memberId, VehicleModel model, Short modelYear) {
        return new Vehicle(memberId, model, modelYear);
    }

    public void changeModelYear(Short modelYear) {
        this.modelYear = modelYear;
    }

    /**
     * accident 가 {@code ON DELETE RESTRICT} 로 참조하므로 물리 삭제가 불가능하다.
     * 이미 삭제된 차량은 시각을 덮어쓰지 않는다 (DELETE 는 멱등).
     */
    public void softDelete(Instant deletedAt) {
        if (this.deletedAt == null) {
            this.deletedAt = deletedAt;
        }
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
