package com.ssafy.a307.member.entity;

/** {@code member.role} 의 CHECK 제약과 대응. 권한 문자열은 {@code ROLE_} 접두사가 붙는다. */
public enum MemberRole {

    USER,
    ADMIN;

    /** {@code hasRole("ADMIN")} 이 {@code ROLE_ADMIN} 권한을 찾으므로 접두사를 붙여 준다. */
    public String authority() {
        return "ROLE_" + name();
    }
}
