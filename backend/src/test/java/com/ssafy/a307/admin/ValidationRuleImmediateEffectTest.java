package com.ssafy.a307.admin;

import com.ssafy.a307.admin.dto.EstimateValidationRuleResponse;
import com.ssafy.a307.admin.dto.EstimateValidationRuleUpdateRequest;
import com.ssafy.a307.admin.service.AdminEstimateValidationRuleService;
import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.estimatevalidation.domain.EstimateFileType;
import com.ssafy.a307.estimatevalidation.domain.ValidationGrade;
import com.ssafy.a307.estimatevalidation.dto.ManualValidationItemRequest;
import com.ssafy.a307.estimatevalidation.dto.ManualValidationRequest;
import com.ssafy.a307.estimatevalidation.service.EstimateValidationService;
import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이상 탐지 임계값 변경이 <b>다음 검증부터 즉시</b> 반영되고 <b>과거 결과는 그대로</b>인지.
 *
 * <p>프롬프트가 요구한 네 단계를 그대로 돈다.
 * <pre>
 * 1. 기존 규칙으로 검증 A → 등급 확인
 * 2. 관리자가 임계값 변경 → 새 버전과 감사 이력 생성
 * 3. 같은 입력으로 검증 B → 새 규칙으로 등급이 달라진다
 * 4. 검증 A 재조회 → 저장된 결과가 그대로다
 * </pre>
 *
 * <p><b>{@code @Transactional} 을 쓰지 않는다.</b> 규칙 변경이 실제로 커밋돼야 다음 검증이
 * 그것을 읽는다 — 한 트랜잭션에 묶으면 "즉시 반영" 이 아니라 "같은 트랜잭션에서 보인다" 를
 * 증명하게 된다.
 *
 * <p>같은 청구액으로 두 번 검증하는 이유는 <b>입력을 고정해야 등급 차이의 원인이 규칙뿐임을</b>
 * 말할 수 있기 때문이다.
 */
@SpringBootTest
@DisplayName("이상 탐지 규칙 즉시 반영")
class ValidationRuleImmediateEffectTest {

    private static final long ADMIN_ID = 98_001L;
    private static final long MEMBER_ID = 98_002L;
    private static final long MODEL_ID = 98_003L;
    private static final long VEHICLE_ID = 98_004L;
    private static final long ACCIDENT_ID = 98_005L;
    private static final String PART = "ZZ_EFFECT_PART";

    /**
     * P75(150,000)를 넘겨 항목 하나가 확인 권장으로 잡히게 만든다.
     * <p>
     * 기본 규칙은 확인 필요 항목 수 임계값이 3이라 1건이면 주의에 그친다. 그 임계값을 1로
     * 낮추면 같은 입력이 확인 필요가 된다 — <b>등급 차이의 원인이 규칙뿐임을</b> 말할 수 있다.
     */
    private static final int CLAIMED = 180_000;

    @Autowired private EstimateValidationService validationService;
    @Autowired private AdminEstimateValidationRuleService ruleService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("""
                insert into member (member_id, provider, provider_user_id, nickname, role, status)
                values (?, 'KAKAO', 'effect-admin', '즉시반영관리자', 'ADMIN', 'ACTIVE')
                """, ADMIN_ID);
        jdbc.update("""
                insert into member (member_id, provider, provider_user_id, nickname, role, status)
                values (?, 'KAKAO', 'effect-user', '즉시반영사용자', 'USER', 'ACTIVE')
                """, MEMBER_ID);
        jdbc.update("""
                insert into vehicle_model(model_id, manufacturer, model_name, vehicle_type, car_class, is_active)
                values(?, 'ZZ즉시반영', '테스트차', 'SEDAN', 'Mid-size', true)
                """, MODEL_ID);
        jdbc.update("insert into vehicle(vehicle_id,member_id,model_id,model_year) values(?,?,?,2024)",
                VEHICLE_ID, MEMBER_ID, MODEL_ID);
        jdbc.update("""
                insert into accident(
                    accident_id, vehicle_id, vehicle_input_type,
                    snapshot_model_id, snapshot_manufacturer, snapshot_model_name,
                    snapshot_vehicle_type, snapshot_car_class, snapshot_model_year)
                values(?, ?, 'REGISTERED', ?, 'ZZ즉시반영', '테스트차', 'SEDAN', 'Mid-size', 2024)
                """, ACCIDENT_ID, VEHICLE_ID, MODEL_ID);
        jdbc.update("""
                insert into part_code(part_code,name_ko,layout_zone,display_order,is_active,code_scope,version)
                values(?, 'ZZ즉시반영부품', 'FRONT', 960, true, 'EXTENDED', 0)
                """, PART);
        jdbc.update("insert into part_name_mapping(raw_name,part_code) values('ZZ즉시반영부품',?)", PART);
        jdbc.update("""
                insert into repair_cost_stat(stat_id,car_class,part_code,damage_type,repair_method,source,
                    case_count,cost_min,cost_p25,cost_median,cost_p75,cost_max)
                values(98006,'Mid-size',?,'Scratched','sheet_metal','PUBLIC',20,90000,110000,130000,150000,180000)
                """, PART);

        Member admin = memberRepository.findById(ADMIN_ID).orElseThrow();
        UserPrincipal principal = UserPrincipal.ofMember(admin);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        jdbc.update("delete from estimate_validation_question where validation_item_id in "
                + "(select validation_item_id from estimate_validation_item where validation_id in "
                + "(select validation_id from estimate_validation where member_id = ?))", MEMBER_ID);
        jdbc.update("delete from estimate_validation where member_id = ?", MEMBER_ID);
        jdbc.update("delete from repair_cost_stat where stat_id = 98006");
        jdbc.update("delete from part_name_mapping where part_code = ?", PART);
        jdbc.update("delete from part_code where part_code = ?", PART);
        jdbc.update("delete from accident where accident_id = ?", ACCIDENT_ID);
        jdbc.update("delete from vehicle where vehicle_id = ?", VEHICLE_ID);
        jdbc.update("delete from vehicle_model where model_id = ?", MODEL_ID);
        jdbc.update("delete from estimate_validation_rule where rule_version > 1");
        jdbc.update("delete from audit_log where actor_member_id = ?", ADMIN_ID);
        jdbc.update("delete from member where member_id in (?, ?)", ADMIN_ID, MEMBER_ID);
    }

    @Test
    @DisplayName("규칙을 바꾸면 새 검증부터 등급이 달라지고 과거 검증 결과는 그대로다")
    void ruleChangeAffectsOnlyNewValidations() {
        // 1. 기존 규칙(P75 배수 1.5)으로 검증 A
        long validationA = validationService.registerManual(MEMBER_ID, request()).validationId();
        var resultA = validationService.result(MEMBER_ID, validationA);

        assertThat(resultA.reviewItemCount())
                .as("확인 권장 항목이 1건 잡혀야 이 시나리오가 성립한다")
                .isEqualTo(1);
        assertThat(resultA.grade())
                .as("확인 필요 항목 수 임계값이 3이라 1건은 주의에 그친다")
                .isEqualTo(ValidationGrade.CAUTION);
        assertThat(ruleVersionOf(validationA))
                .as("어떤 규칙으로 판정했는지 남는다")
                .isEqualTo(1);

        // 2. 관리자가 확인 필요 항목 수 임계값을 3 → 1 로 낮춘다
        EstimateValidationRuleResponse before = ruleService.current();
        EstimateValidationRuleResponse after = ruleService.createNextVersion(
                new EstimateValidationRuleUpdateRequest(
                        new BigDecimal("1.50"), new BigDecimal("0.10"), new BigDecimal("0.20"),
                        1, "즉시 반영 확인", before.ruleVersion()));

        assertThat(after.ruleVersion()).isEqualTo(before.ruleVersion() + 1);
        assertThat(auditRows()).as("변경 이력이 남는다").isNotEmpty();

        // 3. 같은 입력으로 검증 B — 규칙만 달라졌다
        long validationB = validationService.registerManual(MEMBER_ID, request()).validationId();
        var resultB = validationService.result(MEMBER_ID, validationB);

        assertThat(resultB.reviewItemCount()).as("입력이 같으니 잡히는 항목도 같다").isEqualTo(1);
        assertThat(resultB.grade())
                .as("임계값이 1로 낮아져 같은 입력이 이제 확인 필요다")
                .isEqualTo(ValidationGrade.NEEDS_REVIEW);
        assertThat(ruleVersionOf(validationB)).isEqualTo(after.ruleVersion());

        // 4. 검증 A 재조회 — 저장된 결과가 그대로다
        var reReadA = validationService.result(MEMBER_ID, validationA);
        assertThat(reReadA.grade())
                .as("규칙이 바뀌어도 과거 검증은 다시 계산되지 않는다")
                .isEqualTo(ValidationGrade.CAUTION);
        assertThat(ruleVersionOf(validationA)).isEqualTo(1);
    }

    @Test
    @DisplayName("한 검증 처리 안에서는 같은 규칙 스냅샷을 쓴다 — 판정과 기록이 어긋나지 않는다")
    void oneValidationUsesOneSnapshot() {
        long validationId = validationService.registerManual(MEMBER_ID, request()).validationId();

        Integer recorded = ruleVersionOf(validationId);
        assertThat(recorded)
                .as("판정에 쓴 버전과 기록한 버전이 같아야 근거를 되짚을 수 있다")
                .isEqualTo(ruleService.current().ruleVersion());
    }

    private ManualValidationRequest request() {
        return new ManualValidationRequest(
                ACCIDENT_ID, null, EstimateFileType.MANUAL, CLAIMED,
                List.of(new ManualValidationItemRequest(
                        1, "ZZ즉시반영부품", "판금", 1, CLAIMED, 0)));
    }

    private Integer ruleVersionOf(long validationId) {
        return jdbc.queryForObject(
                "select rule_version from estimate_validation where validation_id = ?",
                Integer.class, validationId);
    }

    private List<java.util.Map<String, Object>> auditRows() {
        return jdbc.queryForList("""
                select action_type, before_data, after_data from audit_log
                where target_type = 'ESTIMATE_VALIDATION_RULE' and actor_member_id = ?
                """, ADMIN_ID);
    }
}
