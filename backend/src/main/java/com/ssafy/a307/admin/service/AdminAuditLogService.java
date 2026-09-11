package com.ssafy.a307.admin.service;

import com.ssafy.a307.admin.dto.AdminPageRequest;
import com.ssafy.a307.admin.dto.AdminPageResponse;
import com.ssafy.a307.admin.dto.AuditLogResponse;
import com.ssafy.a307.audit.entity.AuditActionType;
import com.ssafy.a307.audit.entity.AuditLog;
import com.ssafy.a307.audit.entity.AuditTargetType;
import com.ssafy.a307.audit.repository.AuditLogRepository;
import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.member.repository.MemberRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 변경 이력 조회. <b>읽기 전용이다</b> — 수정·삭제 메서드가 없고 API 도 없다.
 *
 * <p>{@code before_data}·{@code after_data} 는 검색 조건이 아니다. 정본이 JSONB 라 검색이
 * 가능하긴 하지만 인덱스가 없어 전체 스캔이 되고, 필요한 필터는 전부 별도 컬럼에 있다.
 *
 * <p>정렬 기본값은 <b>최신순</b>이다. 이력 화면은 "방금 무엇이 바뀌었나" 를 보는 곳이라
 * 오래된 것이 먼저 나오면 페이지를 끝까지 넘겨야 한다.
 */
@Service
@RequiredArgsConstructor
public class AdminAuditLogService {

    private static final Set<String> SORTABLE = Set.of("createdAt", "auditLogId");
    private static final Sort DEFAULT_SORT =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("auditLogId"));

    /**
     * 규칙 관리 화면이 보는 대상 종류.
     *
     * <p>부품 코드·차량 모델·부품명 매핑 변경은 그 화면과 무관하다. 섞어서 보여 주면
     * 관리자가 "내가 방금 바꾼 임계값" 을 찾으려고 수십 행을 넘겨야 한다.
     */
    public static final Set<AuditTargetType> RULE_TARGETS =
            Set.of(AuditTargetType.REPAIR_METHOD_RULE, AuditTargetType.ESTIMATE_VALIDATION_RULE);

    private final AuditLogRepository repository;
    private final MemberRepository memberRepository;

    @Transactional(readOnly = true)
    public AdminPageResponse<AuditLogResponse> search(
            Instant from, Instant to, Long actorMemberId, AuditActionType actionType,
            AuditTargetType targetType, String targetId,
            Integer page, Integer size, String sort) {

        return search(from, to, actorMemberId, actionType,
                targetType == null ? null : Set.of(targetType), targetId, page, size, sort);
    }

    /**
     * 대상 종류를 여러 개로 좁혀 조회한다.
     *
     * @param targetTypes {@code null} 이면 전체. 빈 집합은 "아무것도 해당 없음" 이라
     *                    호출자의 실수를 조용히 전체 조회로 바꾸지 않고 빈 결과를 준다
     */
    @Transactional(readOnly = true)
    public AdminPageResponse<AuditLogResponse> search(
            Instant from, Instant to, Long actorMemberId, AuditActionType actionType,
            Collection<AuditTargetType> targetTypes, String targetId,
            Integer page, Integer size, String sort) {

        Specification<AuditLog> spec = (root, query, builder) -> {
            List<Predicate> conditions = new ArrayList<>();
            if (from != null) conditions.add(builder.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) conditions.add(builder.lessThan(root.get("createdAt"), to));
            if (actorMemberId != null) conditions.add(builder.equal(root.get("actorMemberId"), actorMemberId));
            if (actionType != null) conditions.add(builder.equal(root.get("actionType"), actionType));
            if (targetTypes != null) {
                conditions.add(targetTypes.isEmpty()
                        ? builder.disjunction()
                        : root.get("targetType").in(targetTypes));
            }
            if (targetId != null && !targetId.isBlank()) {
                conditions.add(builder.equal(root.get("targetId"), targetId.strip()));
            }
            return builder.and(conditions.toArray(Predicate[]::new));
        };

        Page<AuditLog> found =
                repository.findAll(spec, AdminPageRequest.of(page, size, sort, DEFAULT_SORT, SORTABLE));
        Map<Long, String> nicknames = nicknamesOf(found.getContent());
        return AdminPageResponse.of(found,
                log -> AuditLogResponse.from(log, nicknames.get(log.getActorMemberId())));
    }

    /**
     * 이 페이지에 나오는 행위자 이름을 <b>한 번의 조회로</b> 모은다.
     *
     * <p>행마다 회원을 찾으면 20행짜리 페이지에 쿼리가 21개 돈다. 페이지 안에서 같은
     * 관리자가 여러 번 나오는 것이 보통이라 중복을 먼저 접는다.
     *
     * <p>탈퇴한 관리자는 {@code actorMemberId} 가 {@code null} 이 되거나 회원 행이 없어
     * 이름을 찾지 못한다. 그때는 {@code null} 로 두고 <b>ID 와 이력 행은 그대로 남긴다</b> —
     * 이름을 모른다고 이력을 감추지 않는다.
     *
     * <p>담는 것은 닉네임 하나뿐이다. 이메일·소셜 ID 는 이력 화면에 필요 없다.
     */
    private Map<Long, String> nicknamesOf(List<AuditLog> logs) {
        Set<Long> actorIds = logs.stream()
                .map(AuditLog::getActorMemberId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        if (actorIds.isEmpty()) return Map.of();

        return memberRepository.findAllById(actorIds).stream()
                .filter(member -> member.getNickname() != null)
                .collect(Collectors.toMap(Member::getMemberId, Member::getNickname,
                        (first, second) -> first));
    }
}
