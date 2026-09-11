package com.ssafy.a307.audit.entity;

/**
 * 감사 로그의 행위 분류. {@code audit_log.action_type} 에 이름 그대로 들어간다.
 *
 * <p><b>CRUD 네 글자로 뭉뚱그리지 않는다.</b> 비활성화와 재활성화는 UPDATE 지만 운영에서는
 * 전혀 다른 사건이라, "누가 언제 이 코드를 껐나" 를 찾을 때 UPDATE 수십 건을 뒤지게 된다.
 */
public enum AuditActionType {

    CREATE,
    UPDATE,
    /** 비활성화. {@code is_active} 를 false 로 바꾼 UPDATE 를 따로 분류한다. */
    DEACTIVATE,
    /** 재활성화. */
    ACTIVATE,
    DELETE
}
