package com.ssafy.a307.vehicle.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
}
