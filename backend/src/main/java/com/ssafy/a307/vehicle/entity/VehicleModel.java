package com.ssafy.a307.vehicle.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 차량 모델 마스터. 서비스가 쓰기만 하지 않고 읽기만 한다 (데이터 구축은 별도 작업).
 */
@Entity
@Table(name = "vehicle_model")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VehicleModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "model_id")
    private Long modelId;

    @Column(name = "manufacturer", nullable = false, length = 50)
    private String manufacturer;

    @Column(name = "model_name", nullable = false, length = 100)
    private String modelName;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 20)
    private VehicleType vehicleType;

    @Column(name = "car_class", nullable = false, length = 20)
    private CarClass carClass;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    /**
     * 낙관적 잠금. 관리자 둘이 같은 모델을 동시에 고칠 때 나중 저장이 앞의 변경을 조용히
     * 덮는 것을 막는다 — Hibernate 가 {@code UPDATE ... WHERE version = ?} 로 바꾸고,
     * 어긋나면 {@code OptimisticLockingFailureException} 이 나 409 로 나간다.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    private VehicleModel(String manufacturer, String modelName,
                         VehicleType vehicleType, CarClass carClass) {
        this.manufacturer = manufacturer;
        this.modelName = modelName;
        this.vehicleType = vehicleType;
        this.carClass = carClass;
        this.active = true;
    }

    /** 관리자 등록. 새 모델은 활성으로 시작한다 — 등록하자마자 쓰라고 만드는 것이다. */
    public static VehicleModel register(String manufacturer, String modelName,
                                        VehicleType vehicleType, CarClass carClass) {
        return new VehicleModel(requireText(manufacturer, "manufacturer"),
                requireText(modelName, "modelName"),
                java.util.Objects.requireNonNull(vehicleType, "vehicleType"),
                java.util.Objects.requireNonNull(carClass, "carClass"));
    }

    /**
     * 관리자 수정. <b>{@code modelId} 는 바꾸지 않는다</b> — 이미 등록된 사용자 차량과 사고
     * 스냅샷이 이 ID 를 가리키고 있다.
     */
    public void modify(String manufacturer, String modelName,
                       VehicleType vehicleType, CarClass carClass) {
        this.manufacturer = requireText(manufacturer, "manufacturer");
        this.modelName = requireText(modelName, "modelName");
        this.vehicleType = java.util.Objects.requireNonNull(vehicleType, "vehicleType");
        this.carClass = java.util.Objects.requireNonNull(carClass, "carClass");
    }

    /**
     * 활성 상태 전환. 비활성 모델은 공개 목록과 신규 차량 등록 후보에서 빠지지만,
     * <b>이미 그 모델로 등록된 차량과 사고 스냅샷은 그대로 조회된다</b> — 행을 지우지 않기 때문이다.
     */
    public void changeStatus(boolean active) {
        this.active = active;
    }

    private static String requireText(String value, String field) {
        String stripped = value == null ? null : value.strip();
        if (stripped == null || stripped.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return stripped;
    }
}
