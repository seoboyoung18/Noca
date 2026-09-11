package com.ssafy.a307.admin;

import com.ssafy.a307.admin.dto.PartCodeCreateRequest;
import com.ssafy.a307.admin.dto.RepairMethodRuleCreateRequest;
import com.ssafy.a307.admin.dto.RepairMethodRuleResponse;
import com.ssafy.a307.admin.dto.StatusUpdateRequest;
import com.ssafy.a307.admin.service.AdminPartCodeService;
import com.ssafy.a307.admin.service.AdminRepairCodeService;
import com.ssafy.a307.admin.service.AdminRepairMethodRuleService;
import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.estimatevalidation.entity.RepairCodeType;
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
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 수리 방식 규칙을 <b>다시 켤 때</b>도 비활성 코드를 막는가.
 *
 * <h2>왜 따로 보는가</h2>
 * 등록({@code POST})은 손상 유형·수리 방식·부품 코드가 활성인지 본다. 그런데 코드 비활성화는
 * "활성 규칙이 쓰고 있으면 409" 만 막으므로, 아래 순서를 밟으면 등록 검사를 우회할 수 있었다.
 *
 * <ol>
 *   <li>규칙을 끈다 — 이제 그 코드를 쓰는 <b>활성</b> 규칙이 없다</li>
 *   <li>코드를 끈다 — 막을 이유가 없어 통과한다</li>
 *   <li>규칙을 다시 켠다 — 겹침만 보고 통과했다</li>
 * </ol>
 *
 * <p>결과는 <b>비활성 코드를 가리키는 활성 규칙</b>이다. 관리자 화면의 드롭다운에는 그 코드가 없는데
 * 결정 경계는 그 코드를 돌려준다. 재활성화는 "새로 쓸 수 있게 만드는" 일이라 등록과 같은 검사를 받는다.
 */
@SpringBootTest
@DisplayName("수리 방식 규칙 재활성화")
class AdminRuleReactivationTest {

    private static final long ADMIN_ID = 97_101L;
    private static final String PART = "ZZ_REACTIVATE_PART";

    @Autowired private AdminRepairMethodRuleService ruleService;
    @Autowired private AdminRepairCodeService codeService;
    @Autowired private AdminPartCodeService partCodeService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("""
                insert into member (member_id, provider, provider_user_id, nickname, role, status)
                values (?, 'KAKAO', 'admin-reactivate', '재활성관리자', 'ADMIN', 'ACTIVE')
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
        jdbcTemplate.update("delete from audit_log where actor_member_id = ?", ADMIN_ID);
        jdbcTemplate.update("delete from member where member_id = ?", ADMIN_ID);
        jdbcTemplate.update("update repair_code set is_active = true");
    }

    @Test
    @DisplayName("수리 방식 코드가 꺼진 동안 규칙을 다시 켜면 400 INACTIVE_CODE 이고 규칙은 꺼진 채 남는다")
    void inactiveRepairMethodBlocksReactivation() {
        RepairMethodRuleResponse target = create("Scratched", null, "coating", 0);
        create("Crushed", null, "exchange", 0);                 // 마지막 활성 규칙 보호를 피하려고 하나 더
        RepairMethodRuleResponse off = turnOff(target);
        deactivate(RepairCodeType.REPAIR_METHOD, "coating");

        AdminOperationException e = catchThrowableOfType(AdminOperationException.class,
                () -> ruleService.changeStatus(target.ruleId(), new StatusUpdateRequest(true, off.version())));

        assertThat(e.code()).isEqualTo(AdminErrorCode.INACTIVE_CODE);
        assertThat(ruleService.findOne(target.ruleId()).active()).isFalse();
        assertThat(auditActions(target.ruleId()))
                .as("거절된 재활성화는 이력을 남기지 않는다")
                .containsExactly("CREATE", "DEACTIVATE");
    }

    @Test
    @DisplayName("손상 유형 코드가 꺼진 동안에도 마찬가지다")
    void inactiveDamageTypeBlocksReactivation() {
        RepairMethodRuleResponse target = create("Separated", null, "coating", 0);
        create("Scratched", null, "coating", 0);
        RepairMethodRuleResponse off = turnOff(target);
        deactivate(RepairCodeType.DAMAGE_TYPE, "Separated");

        AdminOperationException e = catchThrowableOfType(AdminOperationException.class,
                () -> ruleService.changeStatus(target.ruleId(), new StatusUpdateRequest(true, off.version())));

        assertThat(e.code()).isEqualTo(AdminErrorCode.INACTIVE_CODE);
    }

    @Test
    @DisplayName("부품 코드가 꺼진 동안 부품 예외 규칙을 다시 켜면 400 INACTIVE_CODE 다")
    void inactivePartCodeBlocksReactivation() {
        var part = partCodeService.create(new PartCodeCreateRequest(PART, "재활성부품", "FRONT", 960, null));
        RepairMethodRuleResponse target = create("Scratched", PART, "exchange", 10);
        create("Scratched", null, "coating", 0);
        RepairMethodRuleResponse off = turnOff(target);
        partCodeService.changeStatus(PART, new StatusUpdateRequest(false, part.version()));

        AdminOperationException e = catchThrowableOfType(AdminOperationException.class,
                () -> ruleService.changeStatus(target.ruleId(), new StatusUpdateRequest(true, off.version())));

        assertThat(e.code()).isEqualTo(AdminErrorCode.INACTIVE_CODE);
    }

    @Test
    @DisplayName("코드를 다시 켜면 규칙도 다시 켤 수 있다 — 막는 것은 비활성 코드뿐이다")
    void reactivationWorksOnceCodeIsBack() {
        RepairMethodRuleResponse target = create("Scratched", null, "coating", 0);
        create("Crushed", null, "exchange", 0);
        RepairMethodRuleResponse off = turnOff(target);
        deactivate(RepairCodeType.REPAIR_METHOD, "coating");
        activate(RepairCodeType.REPAIR_METHOD, "coating");

        RepairMethodRuleResponse on = ruleService.changeStatus(target.ruleId(),
                new StatusUpdateRequest(true, off.version()));

        assertThat(on.active()).isTrue();
        assertThat(ruleService.decide("Scratched", null, new BigDecimal("5.00"))).contains("coating");
        assertThat(auditActions(target.ruleId())).containsExactly("CREATE", "DEACTIVATE", "ACTIVATE");
    }

    // ── 도우미 ─────────────────────────────────────────────────────────────

    private RepairMethodRuleResponse create(String damageType, String partCode,
                                            String repairMethod, int priority) {
        return ruleService.create(new RepairMethodRuleCreateRequest(
                damageType, partCode, new BigDecimal("0.00"), new BigDecimal("10.00"),
                false, repairMethod, priority));
    }

    private RepairMethodRuleResponse turnOff(RepairMethodRuleResponse rule) {
        return ruleService.changeStatus(rule.ruleId(), new StatusUpdateRequest(false, rule.version()));
    }

    private void deactivate(RepairCodeType type, String code) {
        codeService.changeStatus(type, code, new StatusUpdateRequest(false, versionOf(type, code)));
    }

    private void activate(RepairCodeType type, String code) {
        codeService.changeStatus(type, code, new StatusUpdateRequest(true, versionOf(type, code)));
    }

    private long versionOf(RepairCodeType type, String code) {
        return codeService.findAll(type).stream()
                .filter(c -> c.code().equals(code)).findFirst().orElseThrow().version();
    }

    private List<String> auditActions(Long ruleId) {
        return jdbcTemplate.queryForList("""
                select action_type from audit_log
                where target_type = 'REPAIR_METHOD_RULE' and target_id = ?
                order by audit_log_id
                """, String.class, String.valueOf(ruleId));
    }
}
