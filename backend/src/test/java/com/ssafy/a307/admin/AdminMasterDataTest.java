package com.ssafy.a307.admin;

import com.ssafy.a307.admin.dto.PartCodeCreateRequest;
import com.ssafy.a307.admin.dto.PartCodeUpdateRequest;
import com.ssafy.a307.admin.dto.PartNameMappingAdminResponse;
import com.ssafy.a307.admin.dto.PartNameMappingCreateRequest;
import com.ssafy.a307.admin.dto.PartNameMappingUpdateRequest;
import com.ssafy.a307.admin.dto.StatusUpdateRequest;
import com.ssafy.a307.admin.dto.VehicleModelAdminResponse;
import com.ssafy.a307.admin.dto.VehicleModelCreateRequest;
import com.ssafy.a307.admin.dto.VehicleModelUpdateRequest;
import com.ssafy.a307.admin.service.AdminPartCodeService;
import com.ssafy.a307.admin.service.AdminPartNameMappingService;
import com.ssafy.a307.admin.service.AdminVehicleModelService;
import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.estimatevalidation.entity.PartCodeScope;
import com.ssafy.a307.estimatevalidation.service.PartNameMappingService;
import jakarta.persistence.EntityManager;
import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.member.repository.MemberRepository;
import com.ssafy.a307.vehicle.entity.CarClass;
import com.ssafy.a307.vehicle.entity.VehicleType;
import com.ssafy.a307.vehicle.service.VehicleModelService;
import com.ssafy.a307.vehicle.service.VehicleRegistrationService;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 차량 모델 · 부품 코드 · 부품명 매핑 관리와 그 감사 이력.
 *
 * <p><b>{@code @Transactional} 을 붙이지 않는다.</b> 감사 로그가 변경과 같은 트랜잭션에
 * 들어가는지 보려면 실제로 커밋돼야 하고, 테스트가 트랜잭션을 쥐고 있으면 그 경계가 흐려진다.
 * 대신 높은 ID 대역을 쓰고 {@code @AfterEach} 에서 직접 지운다.
 *
 * <p>actor 는 {@code CurrentMemberProvider} 가 세션에서 꺼내므로 {@link SecurityContextHolder}
 * 에 관리자를 올려 둔다 — 서비스에 ID 를 넘기는 경로가 아예 없다.
 */
@SpringBootTest
@DisplayName("관리자 마스터 데이터")
class AdminMasterDataTest {

    private static final long ADMIN_ID = 96_001L;
    private static final String PART_A = "ZZ_TEST_PART_A";
    private static final String PART_B = "ZZ_TEST_PART_B";

    @Autowired private AdminVehicleModelService vehicleModelService;
    @Autowired private AdminPartCodeService partCodeService;
    @Autowired private AdminPartNameMappingService mappingService;
    @Autowired private VehicleModelService publicVehicleModelService;
    @Autowired private VehicleRegistrationService registrationService;
    @Autowired private PartNameMappingService dictionaryService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;
    @Autowired private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("""
                insert into member (member_id, provider, provider_user_id, nickname, role, status)
                values (?, 'KAKAO', 'admin-master', '마스터관리자', 'ADMIN', 'ACTIVE')
                """, ADMIN_ID);
        Member admin = memberRepository.findById(ADMIN_ID).orElseThrow();
        UserPrincipal principal = UserPrincipal.ofMember(admin);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.update("delete from part_name_mapping where part_code in (?, ?)", PART_A, PART_B);
        jdbcTemplate.update("delete from part_code where part_code in (?, ?)", PART_A, PART_B);
        jdbcTemplate.update("delete from vehicle_model where manufacturer like 'ZZ테스트%'");
        jdbcTemplate.update("delete from audit_log where actor_member_id = ?", ADMIN_ID);
        jdbcTemplate.update("delete from member where member_id = ?", ADMIN_ID);
    }

    // ─────────────────────────────────────────────────── 차량 모델

    @Nested
    @DisplayName("차량 모델")
    class VehicleModels {

        @Test
        @DisplayName("등록·조회·수정·비활성화·재활성화가 모두 이력에 남는다")
        void lifecycle() {
            VehicleModelAdminResponse created = vehicleModelService.create(new VehicleModelCreateRequest(
                    "ZZ테스트모터스", "테스트카", VehicleType.SEDAN, CarClass.MID_SIZE));
            assertThat(created.active()).isTrue();
            assertThat(created.version()).isZero();

            VehicleModelAdminResponse updated = vehicleModelService.update(created.modelId(),
                    new VehicleModelUpdateRequest("ZZ테스트모터스", "테스트카2",
                            VehicleType.SUV, CarClass.FULL_SIZE, created.version()));
            assertThat(updated.modelName()).isEqualTo("테스트카2");
            assertThat(updated.version()).isGreaterThan(created.version());

            VehicleModelAdminResponse off = vehicleModelService.changeStatus(created.modelId(),
                    new StatusUpdateRequest(false, updated.version()));
            assertThat(off.active()).isFalse();

            VehicleModelAdminResponse on = vehicleModelService.changeStatus(created.modelId(),
                    new StatusUpdateRequest(true, off.version()));
            assertThat(on.active()).isTrue();

            assertThat(auditActions("VEHICLE_MODEL", String.valueOf(created.modelId())))
                    .containsExactly("CREATE", "UPDATE", "DEACTIVATE", "ACTIVATE");
        }

        @Test
        @DisplayName("같은 상태를 다시 요청하면 이력이 늘지 않는다 — \"껐다\" 가 두 번 남으면 거짓이 된다")
        void repeatingTheSameStatusIsIdempotent() {
            VehicleModelAdminResponse created = vehicleModelService.create(new VehicleModelCreateRequest(
                    "ZZ테스트멱등", "멱등카", VehicleType.SEDAN, CarClass.MID_SIZE));
            VehicleModelAdminResponse off = vehicleModelService.changeStatus(created.modelId(),
                    new StatusUpdateRequest(false, created.version()));

            VehicleModelAdminResponse again = vehicleModelService.changeStatus(created.modelId(),
                    new StatusUpdateRequest(false, off.version()));

            assertThat(again.active()).isFalse();
            assertThat(again.version())
                    .as("엔티티를 건드리지 않으므로 version 도 오르지 않는다 — 남의 화면이 괜히 버전 충돌을 만나지 않는다")
                    .isEqualTo(off.version());
            assertThat(auditActions("VEHICLE_MODEL", String.valueOf(created.modelId())))
                    .as("DEACTIVATE 는 실제로 꺼진 한 번만 남는다")
                    .containsExactly("CREATE", "DEACTIVATE");
        }

        @Test
        @DisplayName("제조사+차량명 중복은 409 DUPLICATE_VEHICLE_MODEL 이고 비활성 모델과도 겹칠 수 없다")
        void duplicateIsRejected() {
            VehicleModelAdminResponse first = vehicleModelService.create(new VehicleModelCreateRequest(
                    "ZZ테스트모터스", "중복차", VehicleType.SEDAN, CarClass.COMPACT));
            vehicleModelService.changeStatus(first.modelId(), new StatusUpdateRequest(false, first.version()));

            AdminOperationException e = catchThrowableOfType(AdminOperationException.class,
                    () -> vehicleModelService.create(new VehicleModelCreateRequest(
                            "ZZ테스트모터스", "중복차", VehicleType.SUV, CarClass.FULL_SIZE)));

            assertThat(e.code()).isEqualTo(AdminErrorCode.DUPLICATE_VEHICLE_MODEL);
        }

        @Test
        @DisplayName("오래된 version 으로 수정하면 409 VERSION_CONFLICT 다")
        void staleVersionIsRejected() {
            VehicleModelAdminResponse created = vehicleModelService.create(new VehicleModelCreateRequest(
                    "ZZ테스트모터스", "동시수정", VehicleType.SEDAN, CarClass.COMPACT));
            vehicleModelService.update(created.modelId(), new VehicleModelUpdateRequest(
                    "ZZ테스트모터스", "동시수정", VehicleType.SUV, CarClass.COMPACT, created.version()));

            AdminOperationException e = catchThrowableOfType(AdminOperationException.class,
                    () -> vehicleModelService.update(created.modelId(), new VehicleModelUpdateRequest(
                            "ZZ테스트모터스", "덮어쓰기", VehicleType.VAN, CarClass.COMPACT,
                            created.version())));

            assertThat(e.code()).isEqualTo(AdminErrorCode.VERSION_CONFLICT);
        }

        @Test
        @DisplayName("비활성 모델은 공개 목록과 신규 차량 등록 후보에서 빠진다 — 기존 회귀 방어")
        void inactiveModelIsHiddenFromUsers() {
            VehicleModelAdminResponse created = vehicleModelService.create(new VehicleModelCreateRequest(
                    "ZZ테스트모터스", "숨김차", VehicleType.SEDAN, CarClass.COMPACT));

            assertThat(publicVehicleModelService.findAllActive())
                    .anyMatch(model -> model.modelId().equals(created.modelId()));

            vehicleModelService.changeStatus(created.modelId(),
                    new StatusUpdateRequest(false, created.version()));

            assertThat(publicVehicleModelService.findAllActive())
                    .noneMatch(model -> model.modelId().equals(created.modelId()));
            // 신규 차량 등록도 활성 모델만 받는다.
            // registerByModelId 는 Propagation.MANDATORY 라 트랜잭션 안에서 불러야 실제 경로를 탄다.
            assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                    registrationService.registerByModelId(ADMIN_ID, created.modelId(), 2024)))
                    .hasMessageContaining("등록할 수 없는 차량 모델");
        }

        @Test
        @DisplayName("관리자 목록에는 비활성 모델도 나온다 — 다시 켜야 하기 때문이다")
        void adminListIncludesInactive() {
            VehicleModelAdminResponse created = vehicleModelService.create(new VehicleModelCreateRequest(
                    "ZZ테스트모터스", "관리자목록", VehicleType.SEDAN, CarClass.COMPACT));
            vehicleModelService.changeStatus(created.modelId(),
                    new StatusUpdateRequest(false, created.version()));

            assertThat(vehicleModelService.search("관리자목록", null, null, 0, 20, null).content())
                    .extracting("modelId").contains(created.modelId());
            assertThat(vehicleModelService.search("관리자목록", null, true, 0, 20, null).content())
                    .isEmpty();
        }
    }

    // ─────────────────────────────────────────────────── 부품 코드

    @Nested
    @DisplayName("부품 코드")
    class PartCodes {

        @Test
        @DisplayName("등록하면 기본 scope 가 EXTENDED 다 — AI 라벨은 모델 재학습 없이 늘지 않는다")
        void createDefaultsToExtended() {
            var created = partCodeService.create(new PartCodeCreateRequest(
                    PART_A, "테스트부품", "FRONT", 900, null));

            assertThat(created.codeScope()).isEqualTo(PartCodeScope.EXTENDED);
            assertThat(created.active()).isTrue();
            assertThat(created.mappingCount()).isZero();
        }

        @Test
        @DisplayName("중복 코드는 409 DUPLICATE_PART_CODE 다")
        void duplicateIsRejected() {
            partCodeService.create(new PartCodeCreateRequest(PART_A, "테스트부품", "FRONT", 900, null));

            AdminOperationException e = catchThrowableOfType(AdminOperationException.class,
                    () -> partCodeService.create(new PartCodeCreateRequest(
                            PART_A, "다른이름", "REAR", 901, null)));

            assertThat(e.code()).isEqualTo(AdminErrorCode.DUPLICATE_PART_CODE);
        }

        @Test
        @DisplayName("AI 핵심 32종과 확장 코드가 scope 로 구분된다 — display_order 에 기대지 않는다")
        void scopeSeparatesAiLabels() {
            long aiLabels = partCodeService.search(null, null, PartCodeScope.AI_LABEL, null, 0, 200, null)
                    .totalElements();
            long extended = partCodeService.search(null, null, PartCodeScope.EXTENDED, null, 0, 200, null)
                    .totalElements();

            assertThat(aiLabels + extended)
                    .as("모든 부품 코드는 두 집합 중 하나에 속한다")
                    .isEqualTo(partCodeService.search(null, null, null, null, 0, 200, null).totalElements());
        }

        @Test
        @DisplayName("비활성 부품은 런타임 사전에서 빠지지만 매핑 행과 과거 결과는 남는다")
        void deactivationRemovesFromDictionaryOnly() {
            var code = partCodeService.create(new PartCodeCreateRequest(PART_A, "사전테스트", "FRONT", 901, null));
            mappingService.create(new PartNameMappingCreateRequest("ZZ사전테스트부품", PART_A, "부품 비활성 테스트"));

            assertThat(dictionaryService.loadDictionary()).containsKey("zz사전테스트부품");

            partCodeService.changeStatus(PART_A, new StatusUpdateRequest(false, code.version()));

            assertThat(dictionaryService.loadDictionary())
                    .as("비활성 부품의 별칭은 사전에 올라가지 않는다")
                    .doesNotContainKey("zz사전테스트부품");
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from part_name_mapping where part_code = ?", Integer.class, PART_A))
                    .as("매핑 행 자체는 지워지지 않는다")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("같은 상태를 다시 요청해도 참조 검사에 걸리지 않는다 — 이미 그 상태다")
        void repeatingTheSameStatusIsIdempotent() {
            var created = partCodeService.create(new PartCodeCreateRequest(PART_A, "멱등", "FRONT", 905, null));
            var off = partCodeService.changeStatus(PART_A, new StatusUpdateRequest(false, created.version()));

            var again = partCodeService.changeStatus(PART_A, new StatusUpdateRequest(false, off.version()));

            assertThat(again.active()).isFalse();
            assertThat(again.version()).isEqualTo(off.version());
            assertThat(auditActions("PART_CODE", PART_A)).containsExactly("CREATE", "DEACTIVATE");
        }

        @Test
        @DisplayName("오래된 version 으로 수정하면 409 다")
        void staleVersionIsRejected() {
            var created = partCodeService.create(new PartCodeCreateRequest(PART_A, "버전테스트", "FRONT", 902, null));
            partCodeService.update(PART_A, new PartCodeUpdateRequest(
                    "한번수정", "REAR", 903, PartCodeScope.EXTENDED, created.version()));

            AdminOperationException e = catchThrowableOfType(AdminOperationException.class,
                    () -> partCodeService.update(PART_A, new PartCodeUpdateRequest(
                            "두번수정", "TOP", 904, PartCodeScope.EXTENDED, created.version())));

            assertThat(e.code()).isEqualTo(AdminErrorCode.VERSION_CONFLICT);
        }

        @Test
        @DisplayName("물리 삭제 API 가 없다 — 참조가 끊기면 과거 분석·견적의 의미가 사라진다")
        void hasNoDeleteApi() {
            assertThat(AdminPartCodeService.class.getMethods())
                    .extracting("name")
                    .doesNotContain("delete", "remove", "deleteById");
        }
    }

    // ─────────────────────────────────────────────────── 부품명 매핑

    @Nested
    @DisplayName("부품명 매핑")
    class PartNameMappings {

        private static final String REASON = "테스트 목적의 별칭 정리";

        @Test
        @DisplayName("등록·대상 수정·삭제가 되고 사전에 즉시 반영된다 — 캐시가 없다")
        void lifecycleReflectsImmediately() {
            partCodeService.create(new PartCodeCreateRequest(PART_A, "매핑대상A", "FRONT", 910, null));
            partCodeService.create(new PartCodeCreateRequest(PART_B, "매핑대상B", "REAR", 911, null));

            mappingService.create(new PartNameMappingCreateRequest("ZZ즉시반영", PART_A, REASON));
            assertThat(dictionaryService.loadDictionary().get("zz즉시반영").partCode()).isEqualTo(PART_A);

            mappingService.update("ZZ즉시반영", new PartNameMappingUpdateRequest(PART_B, REASON));
            assertThat(dictionaryService.loadDictionary().get("zz즉시반영").partCode())
                    .as("loadDictionary 가 매번 DB 를 읽으므로 무효화가 필요 없다")
                    .isEqualTo(PART_B);

            mappingService.delete("ZZ즉시반영", REASON);
            assertThat(dictionaryService.loadDictionary()).doesNotContainKey("zz즉시반영");

            assertThat(auditActions("PART_NAME_MAPPING", "ZZ즉시반영"))
                    .containsExactly("CREATE", "UPDATE", "DELETE");
        }

        @Test
        @DisplayName("한글·공백·괄호가 든 원문도 그대로 다룬다")
        void handlesKoreanAndSpecialCharacters() {
            partCodeService.create(new PartCodeCreateRequest(PART_A, "특수문자", "FRONT", 912, null));
            String rawName = "앞 범퍼(좌) 교환";

            mappingService.create(new PartNameMappingCreateRequest(rawName, PART_A, REASON));

            assertThat(mappingService.search(rawName, null, null, 0, 20, null).content())
                    .extracting("rawName").containsExactly(rawName);
            mappingService.delete(rawName, REASON);
        }

        // ── 중복 3종. 셋 다 409 지만 관리자가 할 일이 다르므로 코드를 나눈다 ──────────

        @Test
        @DisplayName("정확히 같은 원문은 409 DUPLICATE_PART_NAME_MAPPING 이다")
        void exactDuplicateRawNameIsRejected() {
            partCodeService.create(new PartCodeCreateRequest(PART_A, "중복원문", "FRONT", 917, null));
            mappingService.create(new PartNameMappingCreateRequest("ZZ중복원문", PART_A, REASON));

            assertThat(catchThrowableOfType(AdminOperationException.class,
                    () -> mappingService.create(
                            new PartNameMappingCreateRequest("ZZ중복원문", PART_A, REASON))).code())
                    .isEqualTo(AdminErrorCode.DUPLICATE_PART_NAME_MAPPING);
        }

        @Test
        @DisplayName("compact() 키가 같고 같은 부품이면 409 DUPLICATE — 넣어도 매핑 결과가 안 바뀐다")
        void sameCompactKeySamePartIsRejected() {
            partCodeService.create(new PartCodeCreateRequest(PART_A, "동일대상", "FRONT", 915, null));
            mappingService.create(new PartNameMappingCreateRequest("ZZ동일대상", PART_A, REASON));

            // compact() 는 공백·'-'·'.' 를 지운다 → 둘 다 'zz동일대상'
            AdminOperationException e = catchThrowableOfType(AdminOperationException.class,
                    () -> mappingService.create(
                            new PartNameMappingCreateRequest("ZZ 동일-대상", PART_A, REASON)));

            assertThat(e.code()).isEqualTo(AdminErrorCode.DUPLICATE_PART_NAME_MAPPING);
            assertThat(e).hasMessageContaining("zz동일대상");
        }

        @Test
        @DisplayName("compact() 키가 같은데 다른 부품이면 409 NORMALIZED_NAME_CONFLICT — 조용히 사라지지 않게")
        void sameCompactKeyDifferentPartIsAmbiguous() {
            partCodeService.create(new PartCodeCreateRequest(PART_A, "충돌A", "FRONT", 913, null));
            partCodeService.create(new PartCodeCreateRequest(PART_B, "충돌B", "REAR", 914, null));
            mappingService.create(new PartNameMappingCreateRequest("ZZ충돌원문", PART_A, REASON));

            AdminOperationException e = catchThrowableOfType(AdminOperationException.class,
                    () -> mappingService.create(
                            new PartNameMappingCreateRequest("ZZ.충돌/원문", PART_B, REASON)));

            assertThat(e.code()).isEqualTo(AdminErrorCode.NORMALIZED_NAME_CONFLICT);
            assertThat(e).hasMessageContaining(PART_A);
        }

        @Test
        @DisplayName("런타임 키로만 겹치는 원문은 등록된다 — 두 키를 혼용하지 않는다는 뜻이다")
        void runtimeNormalizerOnlyCollisionIsAccepted() {
            partCodeService.create(new PartCodeCreateRequest(PART_A, "런타임A", "FRONT", 918, null));
            partCodeService.create(new PartCodeCreateRequest(PART_B, "런타임B", "REAR", 919, null));
            mappingService.create(new PartNameMappingCreateRequest("ZZ런타임원문", PART_A, REASON));

            // 런타임 정규화는 작업 접미사 '교환' 을 떼어 'zz런타임원문' 으로 만들지만,
            // compact() 는 떼지 않으므로 'zz런타임원문교환' 이라는 다른 키다.
            var created = mappingService.create(
                    new PartNameMappingCreateRequest("ZZ런타임원문 교환", PART_B, REASON));

            assertThat(created.partCode())
                    .as("compact() 기준으로는 다른 원문이므로 등록을 막지 않는다")
                    .isEqualTo(PART_B);
            assertThat(created.inDictionary())
                    .as("그 결과 런타임 사전에서는 두 항목이 모두 빠진다 — 숨기지 않고 그대로 보여 준다")
                    .isFalse();
            assertThat(dictionaryService.loadDictionary())
                    .doesNotContainKey("zz런타임원문");
        }

        // ── 수정: "모호성을 넓히는가" 가 기준이다 ────────────────────────────────

        @Test
        @DisplayName("혼자인 행의 대상 변경은 허용한다 — 시드 행이 영구히 잠기면 안 된다")
        void retargetOfLoneRowIsAllowed() {
            partCodeService.create(new PartCodeCreateRequest(PART_A, "단독A", "FRONT", 920, null));
            partCodeService.create(new PartCodeCreateRequest(PART_B, "단독B", "REAR", 921, null));
            mappingService.create(new PartNameMappingCreateRequest("ZZ단독행", PART_A, REASON));

            assertThat(mappingService.update("ZZ단독행",
                    new PartNameMappingUpdateRequest(PART_B, REASON)).partCode())
                    .isEqualTo(PART_B);
        }

        @Test
        @DisplayName("없던 모호성을 만드는 대상 변경은 409 다")
        void retargetThatWidensAmbiguityIsRejected() {
            partCodeService.create(new PartCodeCreateRequest(PART_A, "모호A", "FRONT", 922, null));
            partCodeService.create(new PartCodeCreateRequest(PART_B, "모호B", "REAR", 923, null));
            // 같은 compact 키를 가진 두 행을 만든다. 등록 API 로는 만들 수 없으므로
            // 시드가 이미 가지고 있는 상태(1,950개 키)를 DB 로 직접 재현한다.
            mappingService.create(new PartNameMappingCreateRequest("ZZ모호원문", PART_A, REASON));
            jdbcTemplate.update(
                    "insert into part_name_mapping (raw_name, part_code) values (?, ?)",
                    "ZZ 모호.원문", PART_A);

            AdminOperationException e = catchThrowableOfType(AdminOperationException.class,
                    () -> mappingService.update("ZZ모호원문",
                            new PartNameMappingUpdateRequest(PART_B, REASON)));

            assertThat(e.code()).isEqualTo(AdminErrorCode.NORMALIZED_NAME_CONFLICT);
        }

        @Test
        @DisplayName("이미 갈라져 있던 것을 합치는 변경은 허용한다 — 더 나빠지지 않는다")
        void retargetThatNarrowsAmbiguityIsAllowed() {
            partCodeService.create(new PartCodeCreateRequest(PART_A, "합침A", "FRONT", 924, null));
            partCodeService.create(new PartCodeCreateRequest(PART_B, "합침B", "REAR", 925, null));
            mappingService.create(new PartNameMappingCreateRequest("ZZ합침원문", PART_A, REASON));
            jdbcTemplate.update(
                    "insert into part_name_mapping (raw_name, part_code) values (?, ?)",
                    "ZZ 합침.원문", PART_B);

            assertThat(mappingService.update("ZZ합침원문",
                    new PartNameMappingUpdateRequest(PART_B, REASON)).partCode())
                    .as("{A,B} → {B} 로 줄어드는 변경이다")
                    .isEqualTo(PART_B);
        }

        // ── 입력 검증 ───────────────────────────────────────────────────────

        @Test
        @DisplayName("비활성 부품에는 매핑을 걸 수 없다 — 400 INACTIVE_CODE")
        void inactivePartCodeIsRejected() {
            var code = partCodeService.create(new PartCodeCreateRequest(PART_A, "비활성대상", "FRONT", 916, null));
            partCodeService.changeStatus(PART_A, new StatusUpdateRequest(false, code.version()));

            AdminOperationException e = catchThrowableOfType(AdminOperationException.class,
                    () -> mappingService.create(
                            new PartNameMappingCreateRequest("ZZ비활성대상", PART_A, REASON)));

            assertThat(e.code()).isEqualTo(AdminErrorCode.INACTIVE_CODE);
        }

        @Test
        @DisplayName("없는 원문 수정·삭제는 404 다")
        void missingRawNameIsNotFound() {
            assertThat(catchThrowableOfType(AdminOperationException.class,
                    () -> mappingService.delete("ZZ없는원문", REASON)).code())
                    .isEqualTo(AdminErrorCode.ADMIN_TARGET_NOT_FOUND);
        }

        @Test
        @DisplayName("삭제의 변경 사유는 공백일 수 없다 — 본문 DTO 와 같은 규칙이다")
        void blankChangeReasonIsRejectedOnDelete() {
            partCodeService.create(new PartCodeCreateRequest(PART_A, "사유검증", "FRONT", 926, null));
            mappingService.create(new PartNameMappingCreateRequest("ZZ사유검증", PART_A, REASON));

            assertThatThrownBy(() -> mappingService.delete("ZZ사유검증", "   "))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("변경 사유");
            assertThatThrownBy(() -> mappingService.delete("ZZ사유검증", null))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> mappingService.delete("ZZ사유검증", "가".repeat(501)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("500자");

            assertThat(mappingService.search("ZZ사유검증", null, null, 0, 20, null).content())
                    .as("사유가 없으면 아무것도 지워지지 않는다")
                    .hasSize(1);
            mappingService.delete("ZZ사유검증", REASON);
        }

        @Test
        @DisplayName("rawName 이 비면 404 가 아니라 400 이다 — FE 가 고칠 것이 다르다")
        void blankRawNameIsBadRequest() {
            assertThatThrownBy(() -> mappingService.delete("   ", REASON))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("rawName");
        }

        // ── 감사 이력 ───────────────────────────────────────────────────────

        @Test
        @DisplayName("이력에 행위자·시각·사유·before/after 가 함께 남는다")
        void auditKeepsActorReasonAndSnapshots() {
            partCodeService.create(new PartCodeCreateRequest(PART_A, "이력A", "FRONT", 927, null));
            partCodeService.create(new PartCodeCreateRequest(PART_B, "이력B", "REAR", 928, null));
            mappingService.create(new PartNameMappingCreateRequest("ZZ이력원문", PART_A, "최초 등록"));
            mappingService.update("ZZ이력원문", new PartNameMappingUpdateRequest(PART_B, "오분류 정정"));
            mappingService.delete("ZZ이력원문", "중복이라 제거");

            var rows = jdbcTemplate.queryForList("""
                    select action_type, actor_member_id, change_reason, before_data, after_data, created_at
                    from audit_log where target_type = 'PART_NAME_MAPPING' and target_id = ?
                    order by audit_log_id asc
                    """, "ZZ이력원문");

            assertThat(rows).hasSize(3);
            assertThat(rows).extracting("change_reason")
                    .containsExactly("최초 등록", "오분류 정정", "중복이라 제거");
            assertThat(rows).extracting("actor_member_id")
                    .containsOnly(ADMIN_ID);
            assertThat(rows).allSatisfy(row ->
                    assertThat(row.get("created_at")).as("시각은 항상 남는다").isNotNull());

            assertThat((String) rows.get(0).get("before_data")).as("CREATE 는 이전 값이 없다").isNull();
            assertThat((String) rows.get(0).get("after_data")).contains(PART_A);
            assertThat((String) rows.get(1).get("before_data")).contains(PART_A);
            assertThat((String) rows.get(1).get("after_data")).contains(PART_B);
            assertThat((String) rows.get(2).get("before_data")).contains(PART_B);
            assertThat((String) rows.get(2).get("after_data")).as("DELETE 는 이후 값이 없다").isNull();
        }

        @Test
        @DisplayName("이력 기록이 실패하면 매핑 변경도 롤백된다 — 둘은 한 트랜잭션이다")
        void auditFailureRollsBackTheChange() {
            partCodeService.create(new PartCodeCreateRequest(PART_A, "롤백대상", "FRONT", 929, null));

            // 이력 저장만 실패하는 상황을 만든다. 서비스 코드를 바꾸지 않고 재현하려면
            // 이력 테이블을 잠시 치우는 것이 가장 확실하다 — 모킹으로 만든 실패는
            // "트랜잭션이 정말 하나인가" 를 증명하지 못하고 스텁의 동작만 증명한다.
            jdbcTemplate.execute("alter table audit_log rename to audit_log_hidden");
            try {
                assertThatThrownBy(() -> mappingService.create(
                        new PartNameMappingCreateRequest("ZZ롤백원문", PART_A, REASON)))
                        .isInstanceOf(DataAccessException.class);
            } finally {
                jdbcTemplate.execute("alter table audit_log_hidden rename to audit_log");
            }

            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from part_name_mapping where raw_name = ?", Integer.class, "ZZ롤백원문"))
                    .as("이력을 남기지 못했으면 매핑도 남지 않는다")
                    .isZero();
        }

        // ── 과거 데이터 ─────────────────────────────────────────────────────

        @Test
        @DisplayName("매핑을 고쳐도 과거 견적 명세는 그대로다 — 그 표를 매핑한 엔티티가 아예 없다")
        void repairCaseItemIsUnreachableFromTheBackend() {
            // "소급 반영하지 않는다" 를 행 단위로 증명하려면 repair_case_item 에 행이 있어야
            // 하는데, 그 표는 아직 엔티티도 소비자도 없어 H2 테스트 스키마에 옮기지 않았다.
            // 대신 더 강한 사실을 잠근다 — 백엔드에는 그 표를 쓸 수 있는 매핑 자체가 없다.
            // 나중에 엔티티가 생기면 이 테스트가 깨지면서 소급 정책을 다시 결정하게 만든다.
            var mappedTables = entityManager.getMetamodel().getEntities().stream()
                    .map(type -> type.getJavaType().getAnnotation(jakarta.persistence.Table.class))
                    .filter(java.util.Objects::nonNull)
                    .map(jakarta.persistence.Table::name)
                    .toList();

            assertThat(mappedTables)
                    .as("이 표를 매핑한 엔티티가 생기는 순간 매핑 수정의 소급 정책을 다시 정해야 한다")
                    .doesNotContain("repair_case_item", "repair_case");
        }

        // ── 목록 ────────────────────────────────────────────────────────────

        @Test
        @DisplayName("목록이 페이지네이션된다 — 시드만 1만 건이 넘는다")
        void listIsPaginated() {
            var page = mappingService.search(null, null, null, 0, 10, null);

            assertThat(page.content()).hasSizeLessThanOrEqualTo(10);
            assertThat(page.size()).isEqualTo(10);
            assertThat(page.totalElements()).isGreaterThanOrEqualTo(page.content().size());
        }

        @Test
        @DisplayName("페이지 크기에 상한이 있다 — 1만 건을 한 번에 내려보내지 않는다")
        void pageSizeIsCapped() {
            assertThat(mappingService.search(null, null, null, 0, 100_000, null).size())
                    .isEqualTo(com.ssafy.a307.admin.dto.AdminPageRequest.MAX_SIZE);
        }

        @Test
        @DisplayName("rawName 정렬이 페이지를 넘어가도 안정적이다 — 같은 행이 두 페이지에 나오지 않는다")
        void sortIsStableAcrossPages() {
            partCodeService.create(new PartCodeCreateRequest(PART_A, "정렬", "FRONT", 932, null));
            for (String rawName : java.util.List.of("ZZ정렬C", "ZZ정렬A", "ZZ정렬B")) {
                mappingService.create(new PartNameMappingCreateRequest(rawName, PART_A, REASON));
            }

            var first = mappingService.search("ZZ정렬", null, null, 0, 2, null);
            var second = mappingService.search("ZZ정렬", null, null, 1, 2, null);

            assertThat(first.content()).extracting("rawName").containsExactly("ZZ정렬A", "ZZ정렬B");
            assertThat(second.content()).extracting("rawName").containsExactly("ZZ정렬C");
            assertThat(first.totalElements()).isEqualTo(3);
        }

        @Test
        @DisplayName("빈 결과도 정상 응답이다 — 예외가 아니다")
        void emptyResultIsFine() {
            var page = mappingService.search("ZZ절대없는원문XYZ", null, null, 0, 20, null);

            assertThat(page.content()).isEmpty();
            assertThat(page.totalElements()).isZero();
        }

        @Test
        @DisplayName("부품 코드로 거른다")
        void filtersByPartCode() {
            partCodeService.create(new PartCodeCreateRequest(PART_A, "필터A", "FRONT", 931, null));
            mappingService.create(new PartNameMappingCreateRequest("ZZ필터원문", PART_A, REASON));

            assertThat(mappingService.search(null, PART_A, null, 0, 20, null).content())
                    .extracting("partCode").containsOnly(PART_A);
        }
    }

    // ─────────────────────────────────────────────────── 도구

    private java.util.List<String> auditActions(String targetType, String targetId) {
        return jdbcTemplate.queryForList("""
                select action_type from audit_log
                where target_type = ? and target_id = ?
                order by audit_log_id asc
                """, String.class, targetType, targetId);
    }

    @SuppressWarnings("unused")
    private Map<String, PartNameMappingService.MappedPart> dictionary() {
        return dictionaryService.loadDictionary();
    }
}
