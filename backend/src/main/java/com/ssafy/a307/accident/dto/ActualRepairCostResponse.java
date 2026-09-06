package com.ssafy.a307.accident.dto;

import com.ssafy.a307.accident.entity.Accident;

import java.time.Instant;
import java.time.LocalDate;

public record ActualRepairCostResponse(
        Long accidentId,
        Integer actualRepairCost,
        LocalDate repairCompletedDate,
        String repairShopName,
        Instant actualCostRecordedAt
) {
    public static ActualRepairCostResponse from(Accident accident) {
        return new ActualRepairCostResponse(
                accident.getAccidentId(),
                accident.getActualRepairCost(),
                accident.getActualRepairCompletedDate(),
                accident.getRepairShopName(),
                accident.getActualCostRecordedAt()
        );
    }
}
