package com.ssafy.a307.audit.service;

import com.ssafy.a307.audit.entity.AuditActionType;
import com.ssafy.a307.audit.entity.AuditLog;
import com.ssafy.a307.audit.entity.AuditTargetType;
import com.ssafy.a307.audit.repository.AuditLogRepository;
import com.ssafy.a307.common.security.CurrentMemberProvider;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

/**
 * 관리자 변경을 이력으로 남긴다.
 *
 * <p><b>{@code Propagation.MANDATORY} 다.</b> 호출자의 트랜잭션 안에서만 돌아야 한다 —
 * 변경은 커밋됐는데 이력만 사라지거나 그 반대가 되면 이력을 근거로 쓸 수 없다.
 * 트랜잭션 없이 부르면 즉시 실패해 "이력이 빠진 변경 경로" 를 개발 중에 잡는다.
 *
 * <p><b>actor 는 요청에서 받지 않는다.</b> {@link CurrentMemberProvider} 가 세션에서 꺼낸다.
 * 관리자 API 의 어떤 요청 DTO 에도 actor 필드가 없다.
 *
 * <p><b>IP 와 request ID 는 "있으면" 담는다.</b> 이것 때문에 전역 필터를 새로 만들지 않았다.
 * IP 는 {@code ServletRequest.getRemoteAddr()} 만 쓴다 — 프록시 신뢰 설정이 없는 상태에서
 * {@code X-Forwarded-For} 를 믿으면 요청자가 스스로 적은 값이 감사 근거가 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository repository;
    private final CurrentMemberProvider currentMemberProvider;
    private final ObjectMapper objectMapper;

    /** CREATE — 이전 값이 없다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void created(AuditTargetType targetType, String targetId, Object after) {
        created(targetType, targetId, after, null);
    }

    /** UPDATE — 의미 있는 필드의 전후를 모두 남긴다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void updated(AuditTargetType targetType, String targetId, Object before, Object after) {
        updated(targetType, targetId, before, after, null);
    }

    /** 활성 상태 전환. UPDATE 와 분류를 나눠 "언제 껐나" 를 바로 찾을 수 있게 한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void statusChanged(AuditTargetType targetType, String targetId,
                              Object before, Object after, boolean nowActive) {
        write(nowActive ? AuditActionType.ACTIVATE : AuditActionType.DEACTIVATE,
                targetType, targetId, before, after, null);
    }

    /** DELETE — 이후 값이 없다. 지워지기 전 모습을 남긴다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleted(AuditTargetType targetType, String targetId, Object before) {
        deleted(targetType, targetId, before, null);
    }

    // ── 변경 사유를 받는 경로 ────────────────────────────────────────────
    //
    // 사유 없는 3·4인자 형태를 남겨 둔 것은 의도다. "사유를 받는 화면" 과 "아직 받지 않는
    // 화면" 이 실제로 갈리는데, 모든 호출부에 null 을 적어 넣게 하면 그 구분이 코드에서
    // 사라진다. 지금 사유를 받는 대상은 부품명 매핑뿐이다.

    /** @param changeReason 관리자가 적은 사유. 요청 DTO 에서 공백·길이를 이미 검증한 값 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void created(AuditTargetType targetType, String targetId,
                        Object after, String changeReason) {
        write(AuditActionType.CREATE, targetType, targetId, null, after, changeReason);
    }

    /** @param changeReason 관리자가 적은 사유. 요청 DTO 에서 공백·길이를 이미 검증한 값 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void updated(AuditTargetType targetType, String targetId,
                        Object before, Object after, String changeReason) {
        write(AuditActionType.UPDATE, targetType, targetId, before, after, changeReason);
    }

    /** @param changeReason 관리자가 적은 사유. 요청 파라미터에서 공백·길이를 이미 검증한 값 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleted(AuditTargetType targetType, String targetId,
                        Object before, String changeReason) {
        write(AuditActionType.DELETE, targetType, targetId, before, null, changeReason);
    }

    private void write(AuditActionType actionType, AuditTargetType targetType, String targetId,
                       Object before, Object after, String changeReason) {
        repository.save(AuditLog.record(
                currentMemberProvider.currentMemberId(),
                actionType, targetType, targetId,
                toJson(before), toJson(after), changeReason,
                requestId(), remoteAddress()));
    }

    /**
     * 직렬화에 실패해도 변경을 롤백하지 않는다. 스냅샷 하나를 잃는 것보다 관리자의 정상적인
     * 변경이 통째로 막히는 편이 나쁘다 — 대신 어떤 대상이었는지 로그로 남긴다.
     */
    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException e) {
            log.warn("감사 로그 스냅샷 직렬화 실패 type={}", value.getClass().getSimpleName(), e);
            return null;
        }
    }

    /**
     * 공통 인프라가 request ID 를 만들어 두었을 때만 담는다. 없으면 {@code null} 이다 —
     * 이 값을 위해 전역 필터를 새로 넣지 않는다.
     */
    private String requestId() {
        return currentRequest()
                .map(request -> request.getHeader("X-Request-Id"))
                .filter(value -> !value.isBlank())
                .orElse(null);
    }

    /**
     * <b>{@code X-Forwarded-For} 를 읽지 않는다.</b> 프록시 신뢰 설정이 없으면 클라이언트가
     * 임의로 넣을 수 있는 값이라 감사 근거가 되지 못한다. 리버스 프록시 뒤에서는 프록시 IP 가
     * 남는데, 틀린 값을 진짜처럼 남기는 것보다 낫다.
     */
    private String remoteAddress() {
        return currentRequest().map(HttpServletRequest::getRemoteAddr).orElse(null);
    }

    private java.util.Optional<HttpServletRequest> currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? java.util.Optional.of(attributes.getRequest())
                : java.util.Optional.empty();
    }
}
