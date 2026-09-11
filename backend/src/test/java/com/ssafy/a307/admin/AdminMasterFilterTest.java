package com.ssafy.a307.admin;

import com.ssafy.a307.admin.controller.AdminVehicleModelController;
import com.ssafy.a307.admin.dto.PartCodeCreateRequest;
import com.ssafy.a307.admin.dto.PartNameMappingAdminResponse;
import com.ssafy.a307.admin.dto.PartNameMappingCreateRequest;
import com.ssafy.a307.admin.dto.VehicleModelAdminResponse;
import com.ssafy.a307.admin.dto.VehicleModelCreateRequest;
import com.ssafy.a307.admin.service.AdminPartCodeService;
import com.ssafy.a307.admin.service.AdminPartNameMappingService;
import com.ssafy.a307.admin.service.AdminVehicleModelService;
import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.estimatevalidation.entity.PartCodeScope;
import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.member.repository.MemberRepository;
import com.ssafy.a307.vehicle.entity.CarClass;
import com.ssafy.a307.vehicle.entity.VehicleType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 관리자 목록 필터와 삭제 부재 계약 — prompt53 대조에서 비어 있던 것.
 *
 * <ul>
 *   <li>차량 모델 목록의 {@code vehicleType}·{@code carClass} 필터 (prompt50 목표 API 에 있었다)</li>
 *   <li>부품명 매핑 목록에서 <b>AI 핵심 32종 대상 매핑만</b> 볼 수 있어야 한다 — 부품 코드에는
 *       {@code codeScope} 가 있는데 매핑 화면에서는 그 구분이 보이지 않았다</li>
 *   <li>차량 모델도 물리 삭제 API 가 없다는 것 — 부품 코드만 테스트로 고정돼 있었다</li>
 * </ul>
 */
@SpringBootTest
@DisplayName("관리자 목록 필터 보완")
class AdminMasterFilterTest {

    private static final long ADMIN_ID = 96_101L;
    private static final String PART_AI = "ZZ_FILTER_AI";
    private static final String PART_EXT = "ZZ_FILTER_EXT";
    private static final String REASON = "필터 테스트";

    @Autowired private AdminVehicleModelService vehicleModelService;
    @Autowired private AdminPartCodeService partCodeService;
    @Autowired private AdminPartNameMappingService mappingService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("""
                insert into member (member_id, provider, provider_user_id, nickname, role, status)
                values (?, 'KAKAO', 'admin-filter', '필터관리자', 'ADMIN', 'ACTIVE')
                """, ADMIN_ID);
        Member admin = memberRepository.findById(ADMIN_ID).orElseThrow();
        UserPrincipal principal = UserPrincipal.ofMember(admin);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.update("delete from part_name_mapping where part_code in (?, ?)", PART_AI, PART_EXT);
        jdbcTemplate.update("delete from part_code where part_code in (?, ?)", PART_AI, PART_EXT);
        jdbcTemplate.update("delete from vehicle_model where manufacturer like 'ZZ필터%'");
        jdbcTemplate.update("delete from audit_log where actor_member_id = ?", ADMIN_ID);
        jdbcTemplate.update("delete from member where member_id = ?", ADMIN_ID);
    }

    @Nested
    @DisplayName("차량 모델")
    class VehicleModels {

        @BeforeEach
        void seed() {
            vehicleModelService.create(new VehicleModelCreateRequest(
                    "ZZ필터모터스", "세단준중형", VehicleType.SEDAN, CarClass.COMPACT));
            vehicleModelService.create(new VehicleModelCreateRequest(
                    "ZZ필터모터스", "SUV중형", VehicleType.SUV, CarClass.MID_SIZE));
            vehicleModelService.create(new VehicleModelCreateRequest(
                    "ZZ필터모터스", "SUV준중형", VehicleType.SUV, CarClass.COMPACT));
        }

        @Test
        @DisplayName("차량 유형으로 거른다")
        void filtersByVehicleType() {
            assertThat(vehicleModelService.search("ZZ필터", null, VehicleType.SUV, null, null, 0, 20, null)
                    .content())
                    .extracting(VehicleModelAdminResponse::modelName)
                    .containsExactlyInAnyOrder("SUV중형", "SUV준중형");
        }

        @Test
        @DisplayName("차급은 응답 JSON 과 같은 표기('Mid-size')로 받는다")
        void filtersByCarClassCode() {
            assertThat(vehicleModelService.search("ZZ필터", null, null, "Compact", null, 0, 20, null)
                    .content())
                    .extracting(VehicleModelAdminResponse::modelName)
                    .containsExactlyInAnyOrder("세단준중형", "SUV준중형");
            assertThat(vehicleModelService.search("ZZ필터", null, null, "Mid-size", null, 0, 20, null)
                    .content())
                    .extracting(VehicleModelAdminResponse::modelName)
                    .containsExactly("SUV중형");
        }

        @Test
        @DisplayName("두 필터는 AND 다")
        void filtersCombine() {
            assertThat(vehicleModelService.search("ZZ필터", null, VehicleType.SUV, "Compact", null, 0, 20, null)
                    .content())
                    .extracting(VehicleModelAdminResponse::modelName)
                    .containsExactly("SUV준중형");
        }

        @Test
        @DisplayName("모르는 차급 표기는 400 이다 — 조용히 전체를 돌려주지 않는다")
        void unknownCarClassIsRejected() {
            BusinessException e = catchThrowableOfType(BusinessException.class,
                    () -> vehicleModelService.search(null, null, null, "MIDSIZE", null, 0, 20, null));

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
            assertThat(e).hasMessageContaining("Mid-size");
        }

        @Test
        @DisplayName("기존 6인자 검색은 그대로 동작한다 — 필터를 안 주면 전부다")
        void legacySignatureStillWorks() {
            assertThat(vehicleModelService.search("ZZ필터", null, null, 0, 20, null).totalElements())
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("차량 모델도 물리 삭제 API 가 없다 — 사용 중 코드 삭제 차단의 근거")
        void hasNoDeleteApi() {
            assertThat(AdminVehicleModelService.class.getMethods())
                    .extracting("name")
                    .doesNotContain("delete", "remove", "deleteById");
            assertThat(Arrays.stream(AdminVehicleModelController.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(DeleteMapping.class)))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("부품명 매핑 — AI 핵심 32종 구분")
    class MappingScope {

        @BeforeEach
        void seed() {
            partCodeService.create(new PartCodeCreateRequest(
                    PART_AI, "필터AI부품", "FRONT", 970, PartCodeScope.AI_LABEL));
            partCodeService.create(new PartCodeCreateRequest(
                    PART_EXT, "필터확장부품", "REAR", 971, PartCodeScope.EXTENDED));
            mappingService.create(new PartNameMappingCreateRequest("ZZ필터원문AI", PART_AI, REASON));
            mappingService.create(new PartNameMappingCreateRequest("ZZ필터원문확장", PART_EXT, REASON));
        }

        @Test
        @DisplayName("응답에 대상 부품의 scope 가 실린다 — 매핑 화면이 부품 코드를 다시 조회하지 않아도 된다")
        void responseCarriesScope() {
            var rows = mappingService.search("ZZ필터원문", null, null, 0, 20, null).content();

            assertThat(rows).extracting(PartNameMappingAdminResponse::rawName, PartNameMappingAdminResponse::partCodeScope)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple("ZZ필터원문AI", PartCodeScope.AI_LABEL),
                            org.assertj.core.groups.Tuple.tuple("ZZ필터원문확장", PartCodeScope.EXTENDED));
        }

        @Test
        @DisplayName("scope 로 거르면 AI 핵심 32종 대상 매핑만 나온다")
        void filtersByScope() {
            assertThat(mappingService.search("ZZ필터원문", null, null, PartCodeScope.AI_LABEL, 0, 20, null)
                    .content())
                    .extracting(PartNameMappingAdminResponse::rawName)
                    .containsExactly("ZZ필터원문AI");
            assertThat(mappingService.search("ZZ필터원문", null, null, PartCodeScope.EXTENDED, 0, 20, null)
                    .content())
                    .extracting(PartNameMappingAdminResponse::rawName)
                    .containsExactly("ZZ필터원문확장");
        }

        @Test
        @DisplayName("scope 필터의 전체 건수도 페이지 계약과 맞는다 — count 쿼리에도 같은 조건이 있다")
        void countQueryHonoursScope() {
            assertThat(mappingService.search("ZZ필터원문", null, null, PartCodeScope.AI_LABEL, 0, 1, null)
                    .totalElements())
                    .isEqualTo(1);
        }
    }
}
