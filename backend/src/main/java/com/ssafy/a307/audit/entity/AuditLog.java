package com.ssafy.a307.audit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * 관리자 변경 한 건. 정본 {@code audit_log} 를 그대로 쓴다.
 *
 * <p><b>수정·삭제 메서드가 없다.</b> 이력은 쌓이기만 한다 — 고칠 수 있는 이력은 이력이 아니다.
 * 그래서 수정·삭제 API 도 만들지 않았다.
 *
 * <p><b>{@code before_data}·{@code after_data} 는 JSON 문자열이다.</b> 정본은 PostgreSQL
 * {@code JSONB}, H2 는 {@code CLOB} 인데 둘 다 {@code String} 으로 읽고 쓴다. JSONB 연산자를
 * 쓰지 않는 이유는 <b>이 두 컬럼을 검색 조건으로 쓰지 않기</b> 때문이다 — 필터는
 * 행위자·대상·기간뿐이고 그것들은 전부 별도 컬럼에 있다.
 *
 * <p><b>{@code actorMemberId} 는 요청에서 받지 않는다.</b> 세션의 인증 주체에서만 온다.
 * 요청 본문으로 받으면 "누가 바꿨는가" 를 요청자가 스스로 적는 셈이라 이력의 의미가 사라진다.
 *
 * <p><b>{@code changeReason} 은 nullable 이다.</b> 지금 사유를 필수로 받는 경로는 부품명 매핑의
 * 등록·수정·삭제뿐이다. 나머지 대상까지 한꺼번에 필수로 바꾸지 않은 이유는, 사유를 안 적으면
 * 저장을 막겠다는 결정은 <b>대상마다 따로</b> 내려야 하기 때문이다 — 컬럼을 {@code NOT NULL} 로
 * 잠그면 아직 사유를 받지 않는 경로가 전부 깨진다. 대신 사유 없이 기록된 행은 {@code null} 로
 * 남아 "받지 않았다" 와 "빈 문자열을 받았다" 가 구분된다.
 */
@Entity
@Table(name = "audit_log")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    public static final int MAX_TARGET_ID_LENGTH = 100;
    public static final int MAX_REQUEST_ID_LENGTH = 64;
    /** IPv6 최대 표기 길이. 정본은 {@code INET} 이고 H2 는 {@code VARCHAR(45)} 다. */
    public static final int MAX_IP_LENGTH = 45;
    /**
     * 변경 사유. 한 줄 설명을 담을 만큼이되, 스냅샷을 대신할 만큼 길지는 않게 잡았다 —
     * 무엇이 바뀌었는지는 {@code beforeData}/{@code afterData} 가 이미 말한다.
     */
    public static final int MAX_CHANGE_REASON_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_log_id")
    private Long auditLogId;

    /** 탈퇴하면 정본이 {@code ON DELETE SET NULL} 로 끊는다. 이력 행 자체는 남는다. */
    @Column(name = "actor_member_id")
    private Long actorMemberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 50, updatable = false)
    private AuditActionType actionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 50, updatable = false)
    private AuditTargetType targetType;

    /** 대상 식별자. 숫자 PK 와 문자열 PK(부품 코드·원문 부품명)를 함께 담아야 해서 문자열이다. */
    @Column(name = "target_id", nullable = false, length = MAX_TARGET_ID_LENGTH, updatable = false)
    private String targetId;

    @Column(name = "request_id", length = MAX_REQUEST_ID_LENGTH, updatable = false)
    private String requestId;

    @Column(name = "before_data", updatable = false)
    private String beforeData;

    @Column(name = "after_data", updatable = false)
    private String afterData;

    /** 관리자가 적은 변경 사유. 받지 않는 경로에서는 {@code null} 이다. */
    @Column(name = "change_reason", length = MAX_CHANGE_REASON_LENGTH, updatable = false)
    private String changeReason;

    @Column(name = "ip_address", length = MAX_IP_LENGTH, updatable = false)
    private String ipAddress;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private AuditLog(Long actorMemberId, AuditActionType actionType, AuditTargetType targetType,
                     String targetId, String beforeData, String afterData, String changeReason,
                     String requestId, String ipAddress) {
        this.actorMemberId = actorMemberId;
        this.actionType = actionType;
        this.targetType = targetType;
        this.targetId = targetId;
        this.beforeData = beforeData;
        this.afterData = afterData;
        this.changeReason = changeReason;
        this.requestId = requestId;
        this.ipAddress = ipAddress;
    }

    /**
     * @param beforeData   CREATE 면 {@code null}
     * @param afterData    DELETE 면 {@code null}
     * @param changeReason 사유를 받는 경로에서만 채운다. 여기서 길이를 검증하지 않고 자르는
     *                     이유는 {@code targetId} 와 같다 — 이력을 남기지 못해 <b>변경 자체를
     *                     롤백시키는</b> 편이 더 나쁘다. 입력 검증은 요청 DTO 가 먼저 한다
     */
    public static AuditLog record(Long actorMemberId, AuditActionType actionType,
                                  AuditTargetType targetType, String targetId,
                                  String beforeData, String afterData, String changeReason,
                                  String requestId, String ipAddress) {
        if (actionType == null) throw new IllegalArgumentException("actionType is required");
        if (targetType == null) throw new IllegalArgumentException("targetType is required");
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("targetId is required");
        }
        return new AuditLog(actorMemberId, actionType, targetType,
                truncate(targetId, MAX_TARGET_ID_LENGTH), beforeData, afterData,
                truncate(changeReason, MAX_CHANGE_REASON_LENGTH),
                truncate(requestId, MAX_REQUEST_ID_LENGTH), truncate(ipAddress, MAX_IP_LENGTH));
    }

    /**
     * 담을 수 없는 길이는 자른다. 이력을 남기지 못해 <b>변경 자체를 롤백시키는 것</b>보다
     * 잘린 식별자를 남기는 편이 낫다 — 원문 부품명은 200자까지 오는데 컬럼은 100자다.
     */
    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
