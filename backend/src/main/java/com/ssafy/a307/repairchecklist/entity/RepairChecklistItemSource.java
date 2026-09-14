package com.ssafy.a307.repairchecklist.entity;

/**
 * 항목이 어디서 왔는가. {@code ck_rcli_source CHECK (source IN ('AI','COMMON','USER'))}.
 *
 * <p>{@code COMMON} 만 {@code common_code} 를 갖는다 — {@code ck_rcli_link} 가 양방향으로
 * 강제한다. {@code USER} 는 사용자가 직접 추가하는 항목({@code S15P21A307-485})이라 이 작업에서
 * 만들지 않는다.
 */
public enum RepairChecklistItemSource {
    AI,
    COMMON,
    USER
}
