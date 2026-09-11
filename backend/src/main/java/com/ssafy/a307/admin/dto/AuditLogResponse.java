package com.ssafy.a307.admin.dto;

import com.ssafy.a307.audit.entity.AuditActionType;
import com.ssafy.a307.audit.entity.AuditLog;
import com.ssafy.a307.audit.entity.AuditTargetType;

import java.time.Instant;

/**
 * 변경 이력 한 건.
 *
 * @param actorMemberId  바꾼 관리자. 탈퇴하면 {@code null} 이 된다({@code ON DELETE SET NULL}) —
 *                       이력 행 자체는 남는다
 * @param actorNickname  화면에 사람 이름을 띄우기 위한 <b>최소 정보</b>. ID 만으로는 누가
 *                       바꿨는지 알 수 없다. 탈퇴했거나 조회하지 못하면 {@code null} 이다.
 *                       이메일·소셜 ID 같은 다른 개인정보는 담지 않는다
 * @param beforeData    변경 전 스냅샷 JSON 문자열. {@code CREATE} 면 {@code null}
 * @param afterData     변경 후 스냅샷 JSON 문자열. {@code DELETE} 면 {@code null}
 * @param changeReason  관리자가 적은 변경 사유. 사유를 받지 않는 경로의 이력은 {@code null} 이다 —
 *                      "안 받았다" 와 "빈 문자열을 받았다" 를 구분한다
 * @param ipAddress     {@code getRemoteAddr()} 값. 리버스 프록시 뒤에서는 프록시 IP 다 —
 *                      {@code X-Forwarded-For} 는 신뢰 설정이 없어 읽지 않는다
 */
public record AuditLogResponse(
        Long auditLogId,
        Long actorMemberId,
        String actorNickname,
        AuditActionType actionType,
        AuditTargetType targetType,
        String targetId,
        String requestId,
        String beforeData,
        String afterData,
        String changeReason,
        String ipAddress,
        Instant createdAt) {

    /** 행위자 이름을 붙이지 않는 경로. 이름이 필요 없는 호출자가 회원 조회를 하지 않게 한다. */
    public static AuditLogResponse from(AuditLog log) {
        return from(log, null);
    }

    public static AuditLogResponse from(AuditLog log, String actorNickname) {
        return new AuditLogResponse(
                log.getAuditLogId(), log.getActorMemberId(), actorNickname, log.getActionType(),
                log.getTargetType(), log.getTargetId(), log.getRequestId(),
                log.getBeforeData(), log.getAfterData(), log.getChangeReason(),
                log.getIpAddress(), log.getCreatedAt());
    }
}
