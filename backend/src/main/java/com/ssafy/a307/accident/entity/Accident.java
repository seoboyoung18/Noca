package com.ssafy.a307.accident.entity;

import com.ssafy.a307.vehicle.entity.Vehicle;
import com.ssafy.a307.vehicle.entity.CarClass;
import com.ssafy.a307.vehicle.entity.VehicleModel;
import com.ssafy.a307.vehicle.entity.VehicleType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 사고 접수 건.
 * <p>
 * member_id 컬럼이 없다 — accident → vehicle → member 로 도달하는 이행적 종속이라
 * 직접 보관하면 차량 주인과 사고 주인이 어긋나도 DB 가 막지 못한다 (DDL 주석).
 * 소유자 검사는 {@code vehicle.member_id} 로 한다.
 * <p>
 * 실제 수리 정보는 금액·완료일·정비소명과 서버 기록 시각을 한 번에 교체한다.
 */
@Entity
@Table(name = "accident")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Accident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "accident_id")
    private Long accidentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_input_type", nullable = false, length = 10, updatable = false)
    private VehicleInputType vehicleInputType;

    @Column(name = "snapshot_model_id", nullable = false, updatable = false)
    private Long snapshotModelId;

    @Column(name = "snapshot_manufacturer", nullable = false, length = 50, updatable = false)
    private String snapshotManufacturer;

    @Column(name = "snapshot_model_name", nullable = false, length = 100, updatable = false)
    private String snapshotModelName;

    @Enumerated(EnumType.STRING)
    @Column(name = "snapshot_vehicle_type", nullable = false, length = 20, updatable = false)
    private VehicleType snapshotVehicleType;

    @Column(name = "snapshot_car_class", nullable = false, length = 20, updatable = false)
    private CarClass snapshotCarClass;

    @Column(name = "snapshot_model_year", nullable = false, updatable = false)
    private Short snapshotModelYear;

    /** 실제 수리비 입력 API(PUT .../actual-cost)에서 채운다. */
    @Column(name = "actual_repair_cost")
    private Integer actualRepairCost;

    @Column(name = "actual_cost_recorded_at")
    private Instant actualCostRecordedAt;

    @Column(name = "actual_repair_completed_date")
    private LocalDate actualRepairCompletedDate;

    @Column(name = "repair_shop_name", length = 100)
    private String repairShopName;

    /**
     * 목록에서 감춘 시각 (S15P21A307-554). <b>{@code null} 이면 보인다.</b>
     *
     * <p>{@code Vehicle.deletedAt} 과 같은 방식이다 — boolean 이 아니라 시각을 두면
     * "언제부터 안 보였나" 에 답할 수 있다.
     *
     * <p><b>{@code @CreatedDate} 를 붙이지 않는다.</b> 감사 애너테이션이 여기 붙으면 접수하는
     * 순간 값이 채워져 <b>모든 새 사고가 감춰진 채로 태어난다</b>.
     */
    @Column(name = "hidden_at")
    private Instant hiddenAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private Accident(Vehicle vehicle, VehicleInputType vehicleInputType) {
        this.vehicle = vehicle;
        this.vehicleInputType = vehicleInputType;

        VehicleModel model = vehicle.getModel();
        this.snapshotModelId = model.getModelId();
        this.snapshotManufacturer = model.getManufacturer();
        this.snapshotModelName = model.getModelName();
        this.snapshotVehicleType = model.getVehicleType();
        this.snapshotCarClass = model.getCarClass();
        this.snapshotModelYear = vehicle.getModelYear();
    }

    public static Accident open(Vehicle vehicle, VehicleInputType vehicleInputType) {
        return new Accident(vehicle, vehicleInputType);
    }

    /**
     * 목록에서 감춘다 (S15P21A307-554). <b>이미 감춘 것을 또 감추면 처음 시각을 지킨다</b> —
     * 화면이 토글을 두 번 눌러도 "언제부터 안 보였나" 가 흔들리지 않는다.
     * {@code Vehicle.softDelete} 와 같은 판단이다.
     */
    public void hide(Instant hiddenAt) {
        if (this.hiddenAt == null) {
            this.hiddenAt = hiddenAt;
        }
    }

    /** 다시 목록에 보이게 한다. 감춘 적이 없어도 탈이 없다. */
    public void unhide() {
        this.hiddenAt = null;
    }

    public boolean isHidden() {
        return hiddenAt != null;
    }

    /** PUT semantics: all actual-repair fields are replaced together. */
    public void recordActualRepair(
            int actualRepairCost,
            LocalDate actualRepairCompletedDate,
            String repairShopName,
            Instant recordedAt) {
        this.actualRepairCost = actualRepairCost;
        this.actualRepairCompletedDate = actualRepairCompletedDate;
        this.repairShopName = repairShopName;
        this.actualCostRecordedAt = recordedAt;
    }
}
