package com.ssafy.a307.estimatevalidation.entity;

/** {@link RepairCode} 가 표시층을 얹는 canonical code 집합. */
public enum RepairCodeType {

    /** {@code exchange} · {@code sheet_metal} · {@code coating} · {@code repair}. */
    REPAIR_METHOD,
    /** {@code Scratched} · {@code Separated} · {@code Crushed} · {@code Breakage}. */
    DAMAGE_TYPE
}
