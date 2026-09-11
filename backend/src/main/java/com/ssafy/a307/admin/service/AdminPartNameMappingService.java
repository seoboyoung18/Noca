package com.ssafy.a307.admin.service;

import com.ssafy.a307.admin.AdminErrorCode;
import com.ssafy.a307.admin.AdminOperationException;
import com.ssafy.a307.admin.dto.AdminPageRequest;
import com.ssafy.a307.admin.dto.AdminPageResponse;
import com.ssafy.a307.admin.dto.PartNameMappingAdminResponse;
import com.ssafy.a307.admin.dto.PartNameMappingCreateRequest;
import com.ssafy.a307.admin.dto.PartNameMappingUpdateRequest;
import com.ssafy.a307.audit.entity.AuditLog;
import com.ssafy.a307.audit.entity.AuditTargetType;
import com.ssafy.a307.audit.service.AuditLogService;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.estimatevalidation.entity.PartCode;
import com.ssafy.a307.estimatevalidation.entity.PartCodeScope;
import com.ssafy.a307.estimatevalidation.entity.PartNameMapping;
import com.ssafy.a307.estimatevalidation.repository.PartCodeRepository;
import com.ssafy.a307.estimatevalidation.repository.PartNameMappingRepository;
import com.ssafy.a307.estimatevalidation.service.PartNameCompactKey;
import com.ssafy.a307.estimatevalidation.service.PartNameMappingService;
import com.ssafy.a307.estimatevalidation.service.PartNameNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 한글 원문 ↔ 부품 코드 별칭 관리.
 *
 * <h2>다루는 범위: DB 에 적재된 확정 매핑뿐</h2>
 * seed 생성 계약상 {@code part_name_mapping} 에 들어오는 것은 {@code MAPPED} 와
 * {@code MAPPED_EXTENDED} 두 상태에서 나온 <b>확정 매핑 15,308건</b>이다. 나머지 5종
 * ({@code MAPPED_SIDE_UNKNOWN}, {@code MAPPED_SPLIT}, {@code REVIEW_CONFLICT},
 * {@code OUT_OF_SCOPE_PART}, {@code NOT_A_PART})은 표준화 워크북에만 있고 DB 에 행이 없다.
 * <b>이 API 는 그 5종을 만들어 내지 않는다</b> — 후보 검수 워크플로는 후보 테이블·상태 전이·
 * 승인 이력이 먼저 설계돼야 하는 별개의 작업이다.
 *
 * <h2>비교 키는 {@link PartNameCompactKey} 다</h2>
 * 중복 판정은 파이프라인의 {@code compact()} 와 <b>같은 눈</b>으로 한다. 런타임 사전 키
 * ({@link PartNameNormalizer})로 판정하면 파이프라인이 서로 다르다고 본 두 원문을 관리자
 * 화면이 같다고 막게 되어, seed 재생성 결과와 관리자 입력이 허용하는 집합이 갈라진다.
 * 사전 진입 여부 표시({@code normalizedName}, {@code inDictionary})는 여전히 런타임 키다 —
 * 그쪽이 실제 조회에 쓰이는 값이기 때문이다.
 *
 * <h2>정규화 충돌을 조용히 넘기지 않는다</h2>
 * 런타임 사전({@code PartNameMappingService.loadDictionary})은 정규화 키가 겹치면서 가리키는
 * 부품이 다르면 <b>두 항목을 모두 버린다.</b> 관리자가 그것을 모르면 "분명히 등록했는데 매핑이
 * 안 된다" 는 상태가 된다. 그래서 등록·수정 시점에 미리 검사해 409 로 끊는다.
 *
 * <h2>변경은 앞으로 처리할 것에만 적용된다</h2>
 * 매핑을 고쳐도 이미 저장된 {@code repair_case_item.part_code} 는 <b>바뀌지 않는다.</b>
 * 과거 견적·비용 통계를 조용히 재작성하지 않겠다는 뜻이며, 소급 반영이 필요하면 별도의
 * 재적재 작업과 영향 검증이 있어야 한다. 그래서 사유({@code changeReason})를 필수로 받는다 —
 * 나중에 과거 데이터와 현재 매핑이 다른 이유는 이력에만 남는다.
 *
 * <h2>매핑은 삭제할 수 있다</h2>
 * 마스터 코드와 달리 이 행은 <b>별칭</b>이라 지워도 과거 데이터의 의미가 바뀌지 않는다.
 * 이미 끝난 검증은 결과를 자기 행에 저장해 두었고 이 표를 다시 읽지 않는다.
 *
 * <h2>⚠️ 시드 재적재 주의</h2>
 * {@code Docs/Erd/A307_part_name_mapping_seed.sql} 을 다시 돌리면 운영에서 지운 별칭이
 * 되살아난다. 시드는 <b>신규 설치 전용</b>이며 운영 DB 에 재적재하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AdminPartNameMappingService {

    private static final Set<String> SORTABLE = Set.of("rawName");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.asc("rawName"));
    private static final int MAX_RAW_NAME_LENGTH = 200;

    private final PartNameMappingRepository repository;
    private final PartCodeRepository partCodeRepository;
    private final PartNameMappingService dictionaryService;
    private final PartNameCompactKey compactKey;
    private final PartNameNormalizer normalizer;
    private final AuditLogService auditLog;

    /**
     * 목록. <b>현재 사전을 한 번만 읽어</b> 각 행이 실제로 사전에 들어가는지 표시한다 —
     * 행마다 사전을 다시 만들면 1만 5천 건을 페이지 크기만큼 반복해서 읽는다.
     */
    @Transactional(readOnly = true)
    public AdminPageResponse<PartNameMappingAdminResponse> search(
            String keyword, String partCode, Boolean partActive,
            Integer page, Integer size, String sort) {

        return search(keyword, partCode, partActive, null, page, size, sort);
    }

    /**
     * @param scope {@code AI_LABEL} 이면 AI 핵심 32종을 가리키는 매핑만, {@code EXTENDED} 면 견적
     *              확장 코드를 가리키는 매핑만. 생략하면 둘 다다. 구분은 {@code part_code.code_scope}
     *              라는 명시적 속성이며 표시 순서 같은 우연한 값에 기대지 않는다
     */
    @Transactional(readOnly = true)
    public AdminPageResponse<PartNameMappingAdminResponse> search(
            String keyword, String partCode, Boolean partActive, PartCodeScope scope,
            Integer page, Integer size, String sort) {

        Map<String, PartNameMappingService.MappedPart> dictionary = dictionaryService.loadDictionary();
        return AdminPageResponse.of(
                repository.searchForAdmin(blankToNull(keyword), blankToNull(partCode), partActive, scope,
                        AdminPageRequest.of(page, size, sort, DEFAULT_SORT, SORTABLE)),
                mapping -> toResponse(mapping, dictionary));
    }

    @Transactional
    public PartNameMappingAdminResponse create(PartNameMappingCreateRequest request) {
        if (repository.existsById(request.rawName())) {
            throw duplicateRawName(request.rawName());
        }
        PartCode target = activePartCode(request.partCode());
        requireNoCompactKeyCollision(request.rawName(), target.getPartCode());

        PartNameMapping saved = saveNew(request.rawName(), target);

        PartNameMappingAdminResponse after = toResponse(saved, dictionaryService.loadDictionary());
        auditLog.created(AuditTargetType.PART_NAME_MAPPING, saved.getRawName(),
                after, request.changeReason());
        return after;
    }

    @Transactional
    public PartNameMappingAdminResponse update(String rawName, PartNameMappingUpdateRequest request) {
        PartNameMapping mapping = load(requireUsableRawName(rawName));
        PartCode target = activePartCode(request.partCode());
        requireRetargetDoesNotWidenAmbiguity(mapping, target.getPartCode());

        PartNameMappingAdminResponse before = toResponse(mapping, dictionaryService.loadDictionary());
        mapping.changeTarget(target);
        repository.flush();

        PartNameMappingAdminResponse after = toResponse(mapping, dictionaryService.loadDictionary());
        auditLog.updated(AuditTargetType.PART_NAME_MAPPING, mapping.getRawName(),
                before, after, request.changeReason());
        return after;
    }

    @Transactional
    public void delete(String rawName, String changeReason) {
        PartNameMapping mapping = load(requireUsableRawName(rawName));
        PartNameMappingAdminResponse before = toResponse(mapping, dictionaryService.loadDictionary());

        // 지우기 전 모습을 먼저 남긴다 — 같은 트랜잭션이라 삭제가 실패하면 이력도 함께 사라진다.
        auditLog.deleted(AuditTargetType.PART_NAME_MAPPING, mapping.getRawName(),
                before, requireChangeReason(changeReason));
        repository.delete(mapping);
        repository.flush();
    }

    // ── 저장 ──────────────────────────────────────────────────────────────

    /**
     * {@code rawName} 은 PK 라 <b>DB 가 마지막 방어선</b>이다. 위의 {@code existsById} 는
     * 친절한 메시지를 주려는 것이지 동시성 대책이 아니다 — 두 요청이 같은 원문으로 동시에
     * 들어오면 둘 다 검사를 통과하고 INSERT 단계에서 한쪽만 살아남는다. 그 실패를 500 으로
     * 흘리지 않고 같은 409 로 바꾼다.
     *
     * <p><b>compact 키에는 이런 방어선이 없다.</b> 기존 15,308건에 이미 1,950개의 compact 키
     * 중복이 있어 UNIQUE 제약을 걸 수 없고(걸려면 3,000행 넘게 지우거나 병합해야 한다),
     * 따라서 compact 키 충돌의 동시성은 <b>해결되지 않았다.</b> 같은 키의 서로 다른 원문 두 개가
     * 동시에 들어오면 둘 다 저장될 수 있다. 그 경우 런타임 사전이 두 항목을 버리고, 관리자
     * 목록의 {@code inDictionary=false} 로 드러난다.
     */
    private PartNameMapping saveNew(String rawName, PartCode target) {
        try {
            return repository.saveAndFlush(PartNameMapping.of(rawName, target));
        } catch (DataIntegrityViolationException e) {
            // 제약명이나 SQL 원문은 응답에 싣지 않는다.
            throw duplicateRawName(rawName);
        }
    }

    private PartNameMapping load(String rawName) {
        return repository.findById(rawName)
                .orElseThrow(() -> AdminOperationException.notFound("부품명 매핑"));
    }

    // ── 검증 ──────────────────────────────────────────────────────────────

    /**
     * 대상 부품이 없으면 404, 비활성이면 400 이다. 둘을 가르는 이유는 관리자가 할 일이
     * 다르기 때문이다 — 없으면 코드를 만들어야 하고, 꺼져 있으면 켜야 한다.
     */
    private PartCode activePartCode(String partCode) {
        PartCode code = partCodeRepository.findById(partCode)
                .orElseThrow(() -> AdminOperationException.notFound("부품 코드"));
        if (!code.isActive()) {
            throw new AdminOperationException(AdminErrorCode.INACTIVE_CODE,
                    "비활성 부품 코드에는 매핑을 걸 수 없습니다. 먼저 활성화해 주세요. (%s)".formatted(partCode));
        }
        return code;
    }

    /**
     * <b>등록</b>에서는 compact 키가 겹치는 순간 막는다. 같은 부품을 가리켜도 마찬가지다 —
     * 파이프라인이 두 원문을 하나로 보는 이상 뒤에 넣는 행은 아무 일도 하지 않는 중복이다.
     * 다만 <b>오류 코드는 나눈다</b>. 관리자가 할 일이 다르기 때문이다.
     *
     * <ul>
     *   <li>같은 부품 → {@code DUPLICATE_PART_NAME_MAPPING}: 이미 있으니 그냥 두면 된다</li>
     *   <li>다른 부품 → {@code NORMALIZED_NAME_CONFLICT}: 어느 쪽이 맞는지 정해야 한다.
     *       그대로 두면 런타임 사전이 <b>양쪽을 모두 버린다</b></li>
     * </ul>
     *
     * <p><b>비활성 부품의 행도 센다.</b> 지금은 사전에 오르지 않지만 코드를 다시 켜는 순간
     * 충돌이 살아난다 — 그때 조용히 사라지는 것보다 등록 시점에 막는 편이 낫다.
     *
     * <p>사전 대신 원본 행을 훑는 이유는 <b>정확도</b> 때문이다. {@code loadDictionary} 는
     * 모호한 키를 아예 버리므로 "누가 누구와 부딪히는지" 를 알 수 없다. 관리자 쓰기 경로는
     * 드물게 도는 데다 사전 적재도 어차피 같은 전수 조회를 한다.
     */
    private void requireNoCompactKeyCollision(String rawName, String targetPartCode) {
        String key = compactKey.compact(rawName);

        for (PartNameMapping other : repository.findAll()) {
            if (other.getRawName().equals(rawName)) continue;              // 자기 자신
            if (!compactKey.compact(other.getRawName()).equals(key)) continue;

            String otherPart = other.getPartCode().getPartCode();
            if (otherPart.equals(targetPartCode)) {
                throw new AdminOperationException(AdminErrorCode.DUPLICATE_PART_NAME_MAPPING,
                        ("비교 키가 같은 원문이 같은 부품으로 이미 등록돼 있습니다. 새 행을 넣어도 "
                                + "매핑 결과가 달라지지 않습니다. (비교 키 '%s' → 기존 '%s')")
                                .formatted(key, other.getRawName()));
            }
            throw new AdminOperationException(AdminErrorCode.NORMALIZED_NAME_CONFLICT,
                    ("비교 키가 같은데 다른 부품을 가리키는 별칭이 있습니다. 그대로 두면 두 항목이 "
                            + "모두 사전에서 빠집니다. (비교 키 '%s' → 기존 '%s' → %s)")
                            .formatted(key, other.getRawName(), otherPart));
        }
    }

    /**
     * <b>수정</b>은 등록과 규칙이 다르다. {@code rawName} 은 그대로이고 대상만 바뀌므로
     * "겹치면 무조건 거절" 을 그대로 쓰면 <b>시드의 기존 행을 영원히 고칠 수 없다</b> —
     * 시드에는 compact 키가 겹치는 원문이 1,950개 키에 걸쳐 이미 들어 있다.
     *
     * <p>그래서 기준을 <b>"모호성을 넓히는가"</b> 로 잡는다. 같은 compact 키 묶음이 가리키는
     * 서로 다른 부품의 개수가 이 변경으로 <b>늘어나면</b> 거절한다.
     *
     * <table border="1">
     *   <caption>같은 키 묶음의 부품 집합</caption>
     *   <tr><th>변경 전</th><th>변경 후</th><th>판정</th></tr>
     *   <tr><td>{A}</td><td>{B}</td><td>허용 — 혼자인 행의 대상 변경</td></tr>
     *   <tr><td>{A, B}</td><td>{B}</td><td>허용 — 갈라져 있던 것을 합친다</td></tr>
     *   <tr><td>{A}</td><td>{A, B}</td><td>409 — 없던 모호성을 만든다</td></tr>
     *   <tr><td>{A, C}</td><td>{B, C}</td><td>허용 — 이미 모호했고 더 나빠지지 않는다</td></tr>
     * </table>
     *
     * <p>마지막 줄은 의도적인 타협이다. 이미 깨져 있는 시드 행을 관리자가 손도 못 대게
     * 막는 것보다, 더 나빠지지 않는 변경은 통과시키고 {@code inDictionary=false} 로 계속
     * 보이게 하는 편이 낫다.
     */
    private void requireRetargetDoesNotWidenAmbiguity(PartNameMapping mapping, String targetPartCode) {
        String rawName = mapping.getRawName();
        String currentPart = mapping.getPartCode().getPartCode();
        if (currentPart.equals(targetPartCode)) return;                    // 바뀌는 것이 없다

        String key = compactKey.compact(rawName);
        Set<String> siblings = new HashSet<>();
        for (PartNameMapping other : repository.findAll()) {
            if (other.getRawName().equals(rawName)) continue;
            if (compactKey.compact(other.getRawName()).equals(key)) {
                siblings.add(other.getPartCode().getPartCode());
            }
        }
        if (siblings.isEmpty()) return;

        Set<String> before = new HashSet<>(siblings);
        before.add(currentPart);
        Set<String> after = new HashSet<>(siblings);
        after.add(targetPartCode);
        if (after.size() <= before.size()) return;

        throw new AdminOperationException(AdminErrorCode.NORMALIZED_NAME_CONFLICT,
                ("대상을 바꾸면 비교 키가 같은 별칭들이 서로 다른 부품을 가리키게 되어 모두 사전에서 "
                        + "빠집니다. (비교 키 '%s' → 같은 키의 기존 부품 %s)")
                        .formatted(key, before.stream().sorted().toList()));
    }

    /**
     * {@code rawName} 은 이제 경로 변수가 아니라 쿼리 파라미터다. 경로 변수 시절에는 스프링이
     * 빈 값을 애초에 매칭하지 않았지만, 쿼리 파라미터는 {@code ?rawName=} 처럼 빈 값이 그대로
     * 들어온다. 빈 원문으로 {@code findById} 를 돌려 404 를 주는 대신 400 으로 끊는다 —
     * "없다" 와 "안 보냈다" 는 FE 가 고칠 것이 다르다.
     */
    private String requireUsableRawName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "rawName 은 필수입니다.");
        }
        String stripped = rawName.strip();
        if (stripped.length() > MAX_RAW_NAME_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "원문 부품명은 %d자 이하여야 합니다.".formatted(MAX_RAW_NAME_LENGTH));
        }
        return stripped;
    }

    /**
     * 삭제는 본문이 없어 사유가 쿼리 파라미터로 온다. 본문 DTO 의 {@code @NotBlank},
     * {@code @Size} 와 <b>같은 규칙을 손으로 다시 건다</b> — 세 경로 중 하나만 사유 없이
     * 통과하면 이력에 구멍이 생긴다.
     */
    private String requireChangeReason(String changeReason) {
        if (changeReason == null || changeReason.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "변경 사유는 필수입니다.");
        }
        String stripped = changeReason.strip();
        if (stripped.length() > AuditLog.MAX_CHANGE_REASON_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "변경 사유는 %d자 이하여야 합니다.".formatted(AuditLog.MAX_CHANGE_REASON_LENGTH));
        }
        return stripped;
    }

    // ── 응답 ──────────────────────────────────────────────────────────────

    private AdminOperationException duplicateRawName(String rawName) {
        return new AdminOperationException(AdminErrorCode.DUPLICATE_PART_NAME_MAPPING,
                "이미 등록된 원문 부품명입니다. (%s)".formatted(rawName));
    }

    /**
     * {@code normalizedName} 과 {@code inDictionary} 는 <b>런타임 조회 키</b>로 만든다.
     * 중복 판정에 쓰는 compact 키가 아니라, 실제로 사전이 쓰는 값을 보여 줘야 관리자가
     * "왜 이 행이 매칭되지 않는가" 를 눈으로 확인할 수 있다.
     */
    private PartNameMappingAdminResponse toResponse(
            PartNameMapping mapping, Map<String, PartNameMappingService.MappedPart> dictionary) {

        String key = normalizer.normalize(mapping.getRawName());
        PartNameMappingService.MappedPart resolved = dictionary.get(key);
        boolean inDictionary = resolved != null
                && resolved.partCode().equals(mapping.getPartCode().getPartCode());
        return PartNameMappingAdminResponse.of(mapping, key, inDictionary);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
