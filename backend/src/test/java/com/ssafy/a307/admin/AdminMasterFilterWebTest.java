package com.ssafy.a307.admin;

import com.ssafy.a307.admin.dto.PartCodeCreateRequest;
import com.ssafy.a307.admin.dto.PartNameMappingCreateRequest;
import com.ssafy.a307.admin.dto.VehicleModelCreateRequest;
import com.ssafy.a307.admin.service.AdminPartCodeService;
import com.ssafy.a307.admin.service.AdminPartNameMappingService;
import com.ssafy.a307.admin.service.AdminVehicleModelService;
import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.estimatevalidation.entity.PartCodeScope;
import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.member.repository.MemberRepository;
import com.ssafy.a307.vehicle.entity.CarClass;
import com.ssafy.a307.vehicle.entity.VehicleType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 새 목록 필터의 <b>HTTP 바인딩</b>. 서비스 로직은 {@code AdminMasterFilterTest} 가 보고,
 * 여기서는 쿼리 문자열이 어떤 표기로 들어와야 하는지와 잘못된 값이 400 인지만 본다.
 * 설정이 {@code AdminPartNameMappingWebTest} 와 같아 컨텍스트를 함께 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration")
@DisplayName("관리자 목록 필터 HTTP 계약")
class AdminMasterFilterWebTest {

    private static final long ADMIN_ID = 94_201L;
    private static final String PART_AI = "ZZ_WEBFILTER_AI";

    @Autowired private MockMvc mockMvc;
    @Autowired private AdminVehicleModelService vehicleModelService;
    @Autowired private AdminPartCodeService partCodeService;
    @Autowired private AdminPartNameMappingService mappingService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Authentication admin;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("""
                insert into member (member_id, provider, provider_user_id, nickname, role, status)
                values (?, 'KAKAO', 'admin-webfilter', '웹필터관리자', 'ADMIN', 'ACTIVE')
                """, ADMIN_ID);
        Member member = memberRepository.findById(ADMIN_ID).orElseThrow();
        UserPrincipal principal = UserPrincipal.ofMember(member);
        admin = new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(admin);

        vehicleModelService.create(new VehicleModelCreateRequest(
                "ZZ웹필터모터스", "웹필터SUV", VehicleType.SUV, CarClass.MID_SIZE));
        partCodeService.create(new PartCodeCreateRequest(PART_AI, "웹필터AI", "FRONT", 980, PartCodeScope.AI_LABEL));
        mappingService.create(new PartNameMappingCreateRequest("ZZ웹필터원문", PART_AI, "HTTP 필터 검증"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.update("delete from part_name_mapping where part_code = ?", PART_AI);
        jdbcTemplate.update("delete from part_code where part_code = ?", PART_AI);
        jdbcTemplate.update("delete from vehicle_model where manufacturer = 'ZZ웹필터모터스'");
        jdbcTemplate.update("delete from audit_log where actor_member_id = ?", ADMIN_ID);
        jdbcTemplate.update("delete from member where member_id = ?", ADMIN_ID);
    }

    @Test
    @DisplayName("carClass 는 응답과 같은 'Mid-size' 로 거르고, vehicleType 은 enum 이름이다")
    void vehicleFiltersBindFromQuery() throws Exception {
        mockMvc.perform(get("/api/admin/vehicle-models").with(authentication(admin))
                        .param("keyword", "ZZ웹필터").param("vehicleType", "SUV").param("carClass", "Mid-size"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].carClass").value("Mid-size"));

        mockMvc.perform(get("/api/admin/vehicle-models").with(authentication(admin))
                        .param("keyword", "ZZ웹필터").param("vehicleType", "SEDAN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(0)));
    }

    @Test
    @DisplayName("모르는 vehicleType·carClass 는 500 이 아니라 400 INVALID_REQUEST 다")
    void unknownVehicleFilterIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/admin/vehicle-models").with(authentication(admin))
                        .param("vehicleType", "BUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/admin/vehicle-models").with(authentication(admin))
                        .param("carClass", "MID_SIZE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("매핑 목록은 scope 로 거르고 응답에 partCodeScope 가 있다")
    void mappingScopeBindsFromQuery() throws Exception {
        mockMvc.perform(get("/api/admin/part-name-mappings").with(authentication(admin))
                        .param("keyword", "ZZ웹필터").param("scope", "AI_LABEL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].partCodeScope").value("AI_LABEL"));

        mockMvc.perform(get("/api/admin/part-name-mappings").with(authentication(admin))
                        .param("keyword", "ZZ웹필터").param("scope", "EXTENDED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(0)));
    }

    @Test
    @DisplayName("모르는 scope 는 400 이다")
    void unknownScopeIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/admin/part-name-mappings").with(authentication(admin))
                        .param("scope", "CORE32"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}
