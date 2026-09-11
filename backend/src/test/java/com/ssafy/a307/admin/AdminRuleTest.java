package com.ssafy.a307.admin;

import com.ssafy.a307.admin.dto.EstimateValidationRuleResponse;
import com.ssafy.a307.admin.dto.EstimateValidationRuleUpdateRequest;
import com.ssafy.a307.admin.dto.RepairCodeUpdateRequest;
import com.ssafy.a307.admin.dto.RepairMethodRuleCreateRequest;
import com.ssafy.a307.admin.dto.RepairMethodRuleResponse;
import com.ssafy.a307.admin.dto.RepairMethodRuleUpdateRequest;
import com.ssafy.a307.admin.dto.StatusUpdateRequest;
import com.ssafy.a307.admin.service.AdminEstimateValidationRuleService;
import com.ssafy.a307.admin.service.AdminPartCodeService;
import com.ssafy.a307.admin.service.AdminRepairCodeService;
import com.ssafy.a307.admin.service.AdminRepairMethodRuleService;
import com.ssafy.a307.admin.dto.PartCodeCreateRequest;
import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.estimatevalidation.entity.EstimateValidationRule;
import com.ssafy.a307.estimatevalidation.entity.RepairCodeType;
import com.ssafy.a307.estimatevalidation.service.EstimateValidationRuleProvider;
import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 심각도 → 수리 방식 규칙과 견적서 이상 탐지 임계값.
 *
 * <p><b>규칙 테이블을 매 테스트마다 비운다.</b> 시드가 없는 테이블이라(심각도 범위가 확정되지
 * 않아 임계값을 지어낼 근거가 없다) 각 테스트가 자기 규칙만 두고 시작한다.
 *
 * <p>이상 탐지 규칙은 <b>시드 버전 1을 지우지 않는다</b> — 지우면 검증 경로가 통째로 죽는다.
 * 대신 테스트가 만든 상위 버전만 되돌린다.
 */
@SpringBootTest
@DisplayName("관리자 판정 규칙")
class AdminRuleTest {

    private static final long ADMIN_ID = 97_001L;
    private static final String PART = "ZZ_RULE_PART";

    @Autowired private AdminRepairMethodRuleService ruleService;
    @Autowired private AdminRepairCodeService codeService;
    @Autowired private AdminPartCodeService partCodeService;
    @Autowired private AdminEstimateValidationRuleService validationRuleService;
    @Autowired private EstimateValidationRuleProvider ruleProvider;
    @Autowired private MemberRepository memberRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("""
                insert into member (member_id, provider, provider_user_id, nickname, role, status)
                values (?, 'KAKAO', 'admin-rule', '규칙관리자', 'ADMIN', 'ACTIVE')
                """, ADMIN_ID);
        Member admin = memberRepository.findById(ADMIN_ID).orElseThrow();
        UserPrincipal principal = UserPrincipal.ofMember(admin);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities()));

        jdbcTemplate.update("delete from repair_method_rule");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.update("delete from repair_method_rule");
        jdbcTemplate.update("delete from part_code where part_code = ?", PART);
        jdbcTemplate.update("delete from estimate_validation_rule where rule_version > 1");
        jdbcTemplate.update("delete from audit_log where actor_member_id = ?", ADMIN_ID);
        jdbcTemplate.update("delete from member where member_id = ?", ADMIN_ID);
        jdbcTemplate.update("""
                update repair_code set is_active = true, display_name = case code
                    when 'exchange' then '교환' when 'sheet_metal' then '판금'
                    when 'coating' then '도장' when 'repair' then '수리'
                    when 'Scratched' then '스크래치' when 'Separated' then '이격'
                    when 'Crushed' then '찌그러짐' else '파손' end
                """);
    }

    // ───────────────────────────────────── 심각도 → 수리 방식

    @Nested
    @DisplayName("심각도 수리 방식 규칙")
    class RepairMethodRules {

        @Test
        @DisplayName("등록한 규칙이 결정 경계에서 그대로 읽힌다 — 표만 만들고 끝내지 않는다")
        void ruleDrivesDecision() {
            create("Scratched", null, "0.00", "30.00", false, "coating", 0);
            create("Scratched", null, "30.00", "100.00", true, "sheet_metal", 0);

            assertThat(ruleService.decide("Scratched", null, new BigDecimal("10.00"))).contains("coating");
            assertThat(ruleService.decide("Scratched", null, new BigDecimal("50.00"))).contains("sheet_metal");
        }

        @Test
        @DisplayName("경계값은 정확히 한 규칙에만 잡힌다 — [min, max) 이고 마지막만 닫는다")
        void boundaryBelongsToExactlyOneRule() {
            create("Crushed", null, "0.00", "30.00", false, "coating", 0);
            create("Crushed", null, "30.00", "100.00", true, "exchange", 0);

            assertThat(ruleService.decide("Crushed", null, new BigDecimal("29.99"))).contains("coating");
            assertThat(ruleService.decide("Crushed", null, new BigDecimal("30.00")))
                    .as("열린 상한이라 30 은 아래 구간이 아니라 위 구간이다")
                    .contains("exchange");
            assertThat(ruleService.decide("Crushed", null, new BigDecimal("100.00")))
                    .as("마지막 구간은 닫혀 있어 최대값도 잡힌다")
                    .contains("exchange");
            assertThat(ruleService.decide("Crushed", null, new BigDecimal("100.01")))
                    .as("어느 구간에도 없으면 기본값으로 덮지 않는다")
                    .isEmpty();
        }

        @Test
        @DisplayName("하한이 상한보다 크거나 같으면 400 INVALID_SEVERITY_RANGE 다")
        void reversedRangeIsRejected() {
            AdminOperationException e = catchThrowableOfType(AdminOperationException.class,
                    () -> create("Scratched", null, "50.00", "10.00", false, "coating", 0));

            assertThat(e.code()).isEqualTo(AdminErrorCode.INVALID_SEVERITY_RANGE);
        }

        @Test
        @DisplayName("같은 범위에서 활성 구간이 겹치면 409 OVERLAPPING_RULE_RANGE 다")
        void overlappingRangeIsRejected() {
            create("Scratched", null, "0.00", "50.00", false, "coating", 0);

            AdminOperationException e = catchThrowableOfType(AdminOperationException.class,
                    () -> create("Scratched", null, "40.00", "80.00", false, "sheet_metal", 0));

            assertThat(e.code()).isEqualTo(AdminErrorCode.OVERLAPPING_RULE_RANGE);
        }

        @Test
        @DisplayName("적용 범위가 다르면 구간이 겹쳐도 된다 — 손상 유형·부품이 다르면 다른 규칙이다")
        void differentScopeMayOverlap() {
            create("Scratched", null, "0.00", "50.00", false, "coating", 0);

            assertThat(create("Breakage", null, "0.00", "50.00", false, "exchange", 0)).isNotNull();
        }

        @Test
        @DisplayName("부품 예외 규칙이 기본 규칙을 이긴다")
        void partRuleWinsOverDefault() {
            partCodeService.create(new PartCodeCreateRequest(PART, "규칙부품", "FRONT", 950, null));
            create("Scratched", null, "0.00", "100.00", true, "coating", 0);
            create("Scratched", PART, "0.00", "100.00", true, "exchange", 10);

            assertThat(ruleService.decide("Scratched", PART, new BigDecimal("50.00"))).contains("exchange");
            assertThat(ruleService.decide("Scratched", "FRONT_BUMPER", new BigDecimal("50.00")))
                    .contains("coating");
        }

        @Test
        @DisplayName("비활성 코드로는 규칙을 만들 수 없다 — 400 INACTIVE_CODE")
        void inactiveCodeIsRejected() {
            var code = codeService.findAll(RepairCodeType.REPAIR_METHOD).stream()
                    .filter(c -> c.code().equals("repair")).findFirst().orElseThrow();
            codeService.changeStatus(RepairCodeType.REPAIR_METHOD, "repair",
                    new StatusUpdateRequest(false, code.version()));

            AdminOperationException e = catchThrowableOfType(AdminOperationException.class,
                    () -> create("Scratched", null, "0.00", "10.00", false, "repair", 0));

            assertThat(e.code()).isEqualTo(AdminErrorCode.INACTIVE_CODE);
        }

        @Test
        @DisplayName("canonical 목록에 없는 코드는 400 UNKNOWN_CODE 다 — 관리자가 새 코드를 만들 수 없다")
        void unknownCodeIsRejected() {
            AdminOperationException e = catchThrowableOfType(AdminOperationException.class,
                    () -> create("Scratched", null, "0.00", "10.00", false, "polishing", 0));

            assertThat(e.code()).isEqualTo(AdminErrorCode.UNKNOWN_CODE);
        }

        @Test
        @DisplayName("활성 규칙이 쓰는 코드는 비활성화할 수 없다 — 409 REFERENCED_BY_ACTIVE_RULE")
        void codeUsedByActiveRuleCannotBeDeactivated() {
            create("Scratched", null, "0.00", "10.00", false, "coating", 0);
            var code = codeService.findAll(RepairCodeType.REPAIR_METHOD).stream()
                    .filter(c -> c.code().equals("coating")).findFirst().orElseThrow();

            AdminOperationException e = catchThrowableOfType(AdminOperationException.class,
                    () -> codeService.changeStatus(RepairCodeType.REPAIR_METHOD, "coating",
                            new StatusUpdateRequest(false, code.version())));

            assertThat(e.code()).isEqualTo(AdminErrorCode.REFERENCED_BY_ACTIVE_RULE);
        }

        @Test
        @DisplayName("마지막 활성 규칙은 끌 수 없다 — 끄면 결정 경로가 사라진다")
        void lastActiveRuleCannotBeDeactivated() {
            RepairMethodRuleResponse only = create("Scratched", null, "0.00", "10.00", false, "coating", 0);

            AdminOperationException e = catchThrowableOfType(AdminOperationException.class,
                    () -> ruleService.changeStatus(only.ruleId(),
                            new StatusUpdateRequest(false, only.version())));

            assertThat(e.code()).isEqualTo(AdminErrorCode.LAST_ACTIVE_RULE);
        }

        @Test
        @DisplayName("규칙 변경이 다음 결정부터 즉시 반영된다")
        void changeAppliesImmediately() {
            RepairMethodRuleResponse rule = create("Separated", null, "0.00", "100.00", true, "coating", 0);
            assertThat(ruleService.decide("Separated", null, new BigDecimal("50.00"))).contains("coating");

            ruleService.update(rule.ruleId(), new RepairMethodRuleUpdateRequest(
                    new BigDecimal("0.00"), new BigDecimal("100.00"), true, "exchange", 0, rule.version()));

            assertThat(ruleService.decide("Separated", null, new BigDecimal("50.00"))).contains("exchange");
        }

        @Test
        @DisplayName("오래된 version 으로 수정하면 409 다")
        void staleVersionIsRejected() {
            RepairMethodRuleResponse rule = create("Scratched", null, "0.00", "10.00", false, "coating", 0);
            ruleService.update(rule.ruleId(), new RepairMethodRuleUpdateRequest(
                    new BigDecimal("0.00"), new BigDecimal("20.00"), false, "coating", 0, rule.version()));

            AdminOperationException e = catchThrowableOfType(AdminOperationException.class,
                    () -> ruleService.update(rule.ruleId(), new RepairMethodRuleUpdateRequest(
                            new BigDecimal("0.00"), new BigDecimal("30.00"), false, "coating", 0,
                            rule.version())));

            assertThat(e.code()).isEqualTo(AdminErrorCode.VERSION_CONFLICT);
        }

        @Test
        @DisplayName("하한과 상한이 같으면 400 이다 — 아무 점수도 품지 못하는 구간이다")
        void emptyRangeIsRejected() {
            AdminOperationException e = catchThrowableOfType(AdminOperationException.class,
                    () -> create("Scratched", null, "10.00", "10.00", false, "coating", 0));

            assertThat(e.code()).isEqualTo(AdminErrorCode.INVALID_SEVERITY_RANGE);
        }

        @Test
        @DisplayName("저장 정밀도를 넘는 심각도는 500 이 아니라 400 이다")
        void tooPreciseSeverityIsRejected() {
            // NUMERIC(6,2) 에 소수 셋째 자리는 담기지 않는다. 엔티티는 RoundingMode.UNNECESSARY
            // 로 ArithmeticException 을 던지는데, 그것이 그대로 새면 공통 catch-all 이 500 을 낸다.
            // 임계값을 조용히 반올림하는 것도 답이 아니라서 400 으로 끊는다.
            AdminOperationException e = catchThrowableOfType(AdminOperationException.class,
                    () -> create("Scratched", null, "0.001", "10.00", false, "coating", 0));

            assertThat(e.code()).isEqualTo(AdminErrorCode.INVALID_RULE_VALUE);
            assertThat(e).hasMessageContaining("정밀도");
        }

        @Test
        @DisplayName("컬럼이 담을 수 없는 큰 값도 400 이다 — DB 제약 위반으로 흘려보내지 않는다")
        void tooLargeSeverityIsRejected() {
            AdminOperationException e = catchThrowableOfType(AdminOperationException.class,
                    () -> create("Scratched", null, "0.00", "99999.99", false, "coating", 0));

            assertThat(e.code()).isEqualTo(AdminErrorCode.INVALID_RULE_VALUE);
        }

        @Test
        @DisplayName("없는 규칙 ID 는 404 다")
        void missingRuleIdIsNotFound() {
            assertThat(catchThrowableOfType(AdminOperationException.class,
                    () -> ruleService.findOne(999_999_999L)).code())
                    .isEqualTo(AdminErrorCode.ADMIN_TARGET_NOT_FOUND);
            assertThat(catchThrowableOfType(AdminOperationException.class,
                    () -> ruleService.update(999_999_999L, new RepairMethodRuleUpdateRequest(
                            new BigDecimal("0.00"), new BigDecimal("1.00"), false, "coating", 0, 0L))).code())
                    .isEqualTo(AdminErrorCode.ADMIN_TARGET_NOT_FOUND);
        }

        @Test
        @DisplayName("없는 부품 코드로는 규칙을 만들 수 없다 — 404 다")
        void missingPartCodeIsNotFound() {
            assertThat(catchThrowableOfType(AdminOperationException.class,
                    () -> create("Scratched", "ZZ_NO_SUCH_PART", "0.00", "10.00", false, "coating", 0)).code())
                    .isEqualTo(AdminErrorCode.ADMIN_TARGET_NOT_FOUND);
        }

        @Test
        @DisplayName("규칙이 하나도 없으면 결정도 비어 있다 — 기본값으로 덮지 않는다")
        void noRulesMeansNoDecision() {
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from repair_method_rule", Integer.class)).isZero();

            assertThat(ruleService.decide("Scratched", null, new BigDecimal("5.00")))
                    .as("근거 없는 수리 방식이 견적에 들어가는 것보다 \"정하지 못했다\" 가 낫다")
                    .isEmpty();
        }

        @Test
        @DisplayName("잘못된 입력은 규칙도 이력도 남기지 않는다")
        void invalidInputLeavesNothing() {
            long auditsBefore = auditCount("REPAIR_METHOD_RULE");

            catchThrowableOfType(AdminOperationException.class,
                    () -> create("Scratched", null, "50.00", "10.00", false, "coating", 0));

            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from repair_method_rule", Integer.class)).isZero();
            assertThat(auditCount("REPAIR_METHOD_RULE")).isEqualTo(auditsBefore);
        }

        @Test
        @DisplayName("이력 기록이 실패하면 규칙 등록도 롤백된다 — 둘은 한 트랜잭션이다")
        void auditFailureRollsBackTheRule() {
            jdbcTemplate.execute("alter table audit_log rename to audit_log_hidden");
            try {
                assertThatThrownBy(() -> create("Scratched", null, "0.00", "10.00", false, "coating", 0))
                        .isInstanceOf(DataAccessException.class);
            } finally {
                jdbcTemplate.execute("alter table audit_log_hidden rename to audit_log");
            }

            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from repair_method_rule", Integer.class))
                    .as("이력을 남기지 못했으면 규칙도 남지 않는다")
                    .isZero();
        }

        @Test
        @DisplayName("등록·수정·상태 변경이 모두 이력에 남는다")
        void changesAreAudited() {
            RepairMethodRuleResponse first = create("Scratched", null, "0.00", "10.00", false, "coating", 0);
            create("Scratched", null, "10.00", "20.00", false, "sheet_metal", 0);
            ruleService.changeStatus(first.ruleId(), new StatusUpdateRequest(false, first.version()));

            assertThat(auditActions("REPAIR_METHOD_RULE", String.valueOf(first.ruleId())))
                    .containsExactly("CREATE", "DEACTIVATE");
        }
    }

    // ───────────────────────────────────── 이상 탐지 임계값

    @Nested
    @DisplayName("이상 탐지 임계값")
    class ValidationRules {

        @Test
        @DisplayName("현재 규칙은 가장 큰 버전이고 변경하면 새 버전이 즉시 현재가 된다")
        void patchCreatesNextVersion() {
            EstimateValidationRuleResponse before = validationRuleService.current();

            EstimateValidationRuleResponse after = validationRuleService.createNextVersion(
                    update("2.00", "0.15", "0.25", 4, "임계값 상향", before.ruleVersion()));

            assertThat(after.ruleVersion()).isEqualTo(before.ruleVersion() + 1);
            assertThat(validationRuleService.current().ruleVersion()).isEqualTo(after.ruleVersion());
            assertThat(ruleProvider.currentRule().needsReviewItemCount())
                    .as("검증 경로가 읽는 공급자에도 즉시 반영된다")
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("과거 버전은 그대로 남는다 — 어떤 검증이 어떤 기준이었는지 되짚을 수 있다")
        void previousVersionsRemain() {
            EstimateValidationRuleResponse v1 = validationRuleService.current();
            validationRuleService.createNextVersion(update("2.00", "0.15", "0.25", 4, null,
                    v1.ruleVersion()));

            assertThat(validationRuleService.history(0, 20, null).content())
                    .extracting("ruleVersion")
                    .contains(v1.ruleVersion(), v1.ruleVersion() + 1);
        }

        @Test
        @DisplayName("오래된 baseVersion 은 409 다 — 상대의 변경이 한 세대 만에 덮이지 않는다")
        void staleBaseVersionIsRejected() {
            EstimateValidationRuleResponse v1 = validationRuleService.current();
            validationRuleService.createNextVersion(update("2.00", "0.15", "0.25", 4, null,
                    v1.ruleVersion()));

            AdminOperationException e = catchThrowableOfType(AdminOperationException.class,
                    () -> validationRuleService.createNextVersion(
                            update("3.00", "0.05", "0.30", 2, null, v1.ruleVersion())));

            assertThat(e.code()).isEqualTo(AdminErrorCode.VERSION_CONFLICT);
        }

        @Test
        @DisplayName("잘못된 값은 저장되지 않고 이력도 남지 않는다")
        void invalidValueLeavesNothing() {
            EstimateValidationRuleResponse before = validationRuleService.current();
            long auditsBefore = auditCount("ESTIMATE_VALIDATION_RULE");

            // 확인 필요 비율이 주의 비율보다 작다 — 엔티티 불변식이 막는다.
            AdminOperationException e = catchThrowableOfType(AdminOperationException.class,
                    () -> validationRuleService.createNextVersion(
                            update("1.50", "0.30", "0.10", 3, null, before.ruleVersion())));

            assertThat(e.code()).isEqualTo(AdminErrorCode.INVALID_RULE_VALUE);
            assertThat(validationRuleService.current().ruleVersion()).isEqualTo(before.ruleVersion());
            assertThat(auditCount("ESTIMATE_VALIDATION_RULE")).isEqualTo(auditsBefore);
        }

        @Test
        @DisplayName("변경이 이력에 전후 값과 함께 남는다")
        void changeIsAudited() {
            EstimateValidationRuleResponse before = validationRuleService.current();
            validationRuleService.createNextVersion(update("2.00", "0.15", "0.25", 4,
                    "상향 조정", before.ruleVersion()));

            List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    select action_type, actor_member_id, before_data, after_data
                    from audit_log where target_type = 'ESTIMATE_VALIDATION_RULE'
                    order by audit_log_id desc
                    """);
            assertThat(rows).isNotEmpty();
            assertThat(rows.get(0).get("action_type")).isEqualTo("UPDATE");
            assertThat(rows.get(0).get("actor_member_id")).isEqualTo(ADMIN_ID);
            assertThat((String) rows.get(0).get("before_data")).contains("\"needsReviewItemCount\":3");
            assertThat((String) rows.get(0).get("after_data")).contains("\"needsReviewItemCount\":4");
        }

        @Test
        @DisplayName("버전 1은 이관 전 배포 기본값과 같다 — 프로퍼티에서 DB 로 옮기며 값이 바뀌지 않았다")
        void versionOneMatchesTheShippedDefaults() {
            // 25dccdd 시점 backend/src/main/resources/application.properties 의 값이다.
            // 임계값을 프로퍼티에서 DB 로 옮긴 것이지 값을 바꾼 것이 아니므로, 여기가 어긋나면
            // 이관 과정에서 조용히 판정 기준이 달라졌다는 뜻이다.
            var v1 = validationRuleService.history(0, 200, null).content().stream()
                    .filter(rule -> rule.ruleVersion() == 1)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("시드 버전 1이 없다"));

            assertThat(v1.severeOverP75Multiplier()).isEqualByComparingTo("1.5");
            assertThat(v1.cautionTotalDifferenceRatio()).isEqualByComparingTo("0.10");
            assertThat(v1.needsReviewTotalDifferenceRatio()).isEqualByComparingTo("0.20");
            assertThat(v1.needsReviewItemCount()).isEqualTo(3);
            assertThat(v1.referencePercentile()).isEqualTo(75);
            assertThat(v1.changedBy()).as("배포 기본값 이관이라 변경자가 없다").isNull();
        }

        @Test
        @DisplayName("같은 값으로 다시 저장하면 새 버전도 이력도 생기지 않는다")
        void unchangedValuesCreateNoVersion() {
            EstimateValidationRuleResponse before = validationRuleService.current();
            long auditsBefore = auditCount("ESTIMATE_VALIDATION_RULE");

            EstimateValidationRuleResponse same = validationRuleService.createNextVersion(update(
                    before.severeOverP75Multiplier().toPlainString(),
                    before.cautionTotalDifferenceRatio().toPlainString(),
                    before.needsReviewTotalDifferenceRatio().toPlainString(),
                    before.needsReviewItemCount(), "사유만 새로 적었다", before.ruleVersion()));

            assertThat(same.ruleVersion())
                    .as("버전 번호를 소모하면 rule_version 이 가리키는 기준 변경 지점이 의미를 잃는다")
                    .isEqualTo(before.ruleVersion());
            assertThat(auditCount("ESTIMATE_VALIDATION_RULE")).isEqualTo(auditsBefore);
        }

        @Test
        @DisplayName("scale 이 달라도 값이 같으면 무변경이다 — 0.10 과 0.1000 은 같은 임계값이다")
        void sameValueWithDifferentScaleIsUnchanged() {
            EstimateValidationRuleResponse before = validationRuleService.current();

            EstimateValidationRuleResponse same = validationRuleService.createNextVersion(
                    update("1.5", "0.1", "0.2", 3, null, before.ruleVersion()));

            assertThat(same.ruleVersion()).isEqualTo(before.ruleVersion());
        }

        @Test
        @DisplayName("컬럼 정밀도를 넘는 임계값은 400 이다 — 409 로 뭉개지 않는다")
        void tooLargeThresholdIsBadRequest() {
            EstimateValidationRuleResponse before = validationRuleService.current();

            // severe_over_p75_multiplier 는 NUMERIC(5,2) 라 999.99 까지다.
            AdminOperationException e = catchThrowableOfType(AdminOperationException.class,
                    () -> validationRuleService.createNextVersion(
                            update("1000.00", "0.10", "0.20", 3, null, before.ruleVersion())));

            assertThat(e.code())
                    .as("값 문제를 VERSION_CONFLICT 로 알려주면 관리자가 재조회만 반복한다")
                    .isEqualTo(AdminErrorCode.INVALID_RULE_VALUE);
            assertThat(validationRuleService.current().ruleVersion()).isEqualTo(before.ruleVersion());
        }

        @Test
        @DisplayName("배수가 1.0 이하면 400 이다 — 등호 포함")
        void multiplierMustExceedOne() {
            EstimateValidationRuleResponse before = validationRuleService.current();

            for (String multiplier : List.of("1.00", "0.90")) {
                assertThat(catchThrowableOfType(AdminOperationException.class,
                        () -> validationRuleService.createNextVersion(
                                update(multiplier, "0.10", "0.20", 3, null, before.ruleVersion()))).code())
                        .as("배수 %s", multiplier)
                        .isEqualTo(AdminErrorCode.INVALID_RULE_VALUE);
            }
            assertThat(validationRuleService.current().ruleVersion()).isEqualTo(before.ruleVersion());
        }

        @Test
        @DisplayName("음수 차이율과 항목 수 0 은 400 이다")
        void negativeRatioAndZeroCountAreRejected() {
            EstimateValidationRuleResponse before = validationRuleService.current();

            assertThat(catchThrowableOfType(AdminOperationException.class,
                    () -> validationRuleService.createNextVersion(
                            update("1.50", "-0.10", "0.20", 3, null, before.ruleVersion()))).code())
                    .isEqualTo(AdminErrorCode.INVALID_RULE_VALUE);
            assertThat(catchThrowableOfType(AdminOperationException.class,
                    () -> validationRuleService.createNextVersion(
                            update("1.50", "0.10", "0.20", 0, null, before.ruleVersion()))).code())
                    .isEqualTo(AdminErrorCode.INVALID_RULE_VALUE);
        }

        @Test
        @DisplayName("기준 백분위는 요청으로 받지 않고 판정 엔진이 읽는 값과 늘 같다")
        void referencePercentileIsNotEditable() {
            assertThat(EstimateValidationRuleUpdateRequest.class.getRecordComponents())
                    .as("저장만 되고 읽는 코드가 없는 설정을 수정 가능한 것처럼 노출하지 않는다")
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .doesNotContain("referencePercentile");

            EstimateValidationRuleResponse before = validationRuleService.current();
            EstimateValidationRuleResponse next = validationRuleService.createNextVersion(
                    update("1.60", "0.11", "0.21", 3, null, before.ruleVersion()));

            assertThat(next.referencePercentile())
                    .as("EstimateValidationEngine 이 repair_cost_stat.cost_p75 를 직접 읽는다")
                    .isEqualTo(EstimateValidationRule.FIXED_REFERENCE_PERCENTILE);
        }

        @Test
        @DisplayName("이력이 최신순으로 페이지네이션된다")
        void historyIsNewestFirstAndPaginated() {
            EstimateValidationRuleResponse v1 = validationRuleService.current();
            var v2 = validationRuleService.createNextVersion(
                    update("1.60", "0.10", "0.20", 3, null, v1.ruleVersion()));
            var v3 = validationRuleService.createNextVersion(
                    update("1.70", "0.10", "0.20", 3, null, v2.ruleVersion()));

            var page = validationRuleService.history(0, 2, null);

            assertThat(page.content()).extracting(EstimateValidationRuleResponse::ruleVersion)
                    .as("최신 버전이 앞이다")
                    .containsExactly(v3.ruleVersion(), v2.ruleVersion());
            assertThat(page.size()).isEqualTo(2);
            assertThat(page.totalElements()).isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("규칙 행이 하나도 없으면 조용히 기본값으로 넘어가지 않고 503 이다")
        void missingRuleFailsLoudly() {
            jdbcTemplate.update("update estimate_validation set rule_version = null");
            jdbcTemplate.update("delete from estimate_validation_rule");
            try {
                assertThat(catchThrowableOfType(com.ssafy.a307.common.exception.BusinessException.class,
                        () -> ruleProvider.currentRule()).getErrorCode())
                        .isEqualTo(com.ssafy.a307.common.exception.ErrorCode.SERVICE_UNAVAILABLE);
            } finally {
                jdbcTemplate.update("""
                        insert into estimate_validation_rule (
                            rule_version, reference_percentile, severe_over_p75_multiplier,
                            caution_total_difference_ratio, needs_review_total_difference_ratio,
                            needs_review_item_count, changed_by, change_note)
                        values (1, 75, 1.50, 0.1000, 0.2000, 3, null, '복원')
                        """);
            }
        }
    }

    // ───────────────────────────────────── canonical code 표시층

    @Nested
    @DisplayName("canonical code 표시층")
    class RepairCodes {

        @Test
        @DisplayName("표시명과 순서만 바꿀 수 있고 등록·삭제 API 는 없다")
        void onlyDisplayFieldsAreMutable() {
            var code = codeService.findAll(RepairCodeType.DAMAGE_TYPE).stream()
                    .filter(c -> c.code().equals("Crushed")).findFirst().orElseThrow();

            var updated = codeService.update(RepairCodeType.DAMAGE_TYPE, "Crushed",
                    new RepairCodeUpdateRequest("눌림", 9, code.version()));

            assertThat(updated.displayName()).isEqualTo("눌림");
            assertThat(updated.code()).as("코드 자체는 바뀌지 않는다").isEqualTo("Crushed");
            assertThat(AdminRepairCodeService.class.getMethods())
                    .extracting("name")
                    .doesNotContain("create", "delete", "register");
        }

        @Test
        @DisplayName("코드 8종이 그대로다 — 관리자가 늘리거나 줄일 수 없다")
        void codeSetIsFixed() {
            assertThat(codeService.findAll(null)).hasSize(8);
            assertThat(codeService.findAll(RepairCodeType.REPAIR_METHOD)).extracting("code")
                    .containsExactlyInAnyOrder("exchange", "sheet_metal", "coating", "repair");
            assertThat(codeService.findAll(RepairCodeType.DAMAGE_TYPE)).extracting("code")
                    .containsExactlyInAnyOrder("Scratched", "Separated", "Crushed", "Breakage");
        }
    }

    // ───────────────────────────────────── 도구

    private RepairMethodRuleResponse create(String damageType, String partCode,
                                            String min, String max, boolean maxInclusive,
                                            String repairMethod, int priority) {
        return ruleService.create(new RepairMethodRuleCreateRequest(
                damageType, partCode, new BigDecimal(min), new BigDecimal(max),
                maxInclusive, repairMethod, priority));
    }

    /**
     * {@code referencePercentile} 인자가 없다. 판정 엔진이 {@code cost_p75} 컬럼을 직접 읽어
     * 그 값을 소비하지 않으므로 수정 대상에서 뺐다 — 자세한 것은
     * {@code EstimateValidationRule.FIXED_REFERENCE_PERCENTILE} Javadoc 에 있다.
     */
    private static EstimateValidationRuleUpdateRequest update(
            String multiplier, String caution, String needsReview,
            int itemCount, String note, int baseVersion) {
        return new EstimateValidationRuleUpdateRequest(
                new BigDecimal(multiplier), new BigDecimal(caution),
                new BigDecimal(needsReview), itemCount, note, baseVersion);
    }

    private List<String> auditActions(String targetType, String targetId) {
        return jdbcTemplate.queryForList("""
                select action_type from audit_log
                where target_type = ? and target_id = ? order by audit_log_id asc
                """, String.class, targetType, targetId);
    }

    private long auditCount(String targetType) {
        return jdbcTemplate.queryForObject(
                "select count(*) from audit_log where target_type = ?", Long.class, targetType);
    }
}
