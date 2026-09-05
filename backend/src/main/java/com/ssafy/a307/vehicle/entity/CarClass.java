package com.ssafy.a307.vehicle.entity;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * {@code ck_vm_class} 가 허용하는 데이터셋 4단계.
 * {@code Mid-size} 처럼 하이픈이 들어가 상수명으로 쓸 수 없어 {@code code} 를 따로 둔다.
 * 저장은 {@link CarClassConverter}, JSON 직렬화는 {@link JsonValue} 가 담당한다.
 */
public enum CarClass {

    CITY_CAR("CityCar"),
    COMPACT("Compact"),
    MID_SIZE("Mid-size"),
    FULL_SIZE("Full-size");

    private final String code;

    CarClass(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    public static CarClass fromCode(String code) {
        return Arrays.stream(values())
                .filter(carClass -> carClass.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("알 수 없는 car_class: " + code));
    }
}
