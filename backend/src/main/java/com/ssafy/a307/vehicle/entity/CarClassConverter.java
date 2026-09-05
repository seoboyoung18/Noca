package com.ssafy.a307.vehicle.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class CarClassConverter implements AttributeConverter<CarClass, String> {

    @Override
    public String convertToDatabaseColumn(CarClass attribute) {
        return attribute == null ? null : attribute.getCode();
    }

    @Override
    public CarClass convertToEntityAttribute(String dbData) {
        return dbData == null ? null : CarClass.fromCode(dbData);
    }
}
