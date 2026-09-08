package com.ssafy.a307.accident.service;

import com.ssafy.a307.accident.dto.AccidentCreateRequest;
import com.ssafy.a307.accident.dto.DirectVehicleInput;
import com.ssafy.a307.accident.repository.AccidentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@SpringBootTest
@DisplayName("사고 즉시 입력 트랜잭션")
class AccidentCreationRollbackTest {

    private static final long MEMBER_ID = 8_801L;

    @Autowired
    private AccidentService accidentService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockitoBean
    private AccidentRepository accidentRepository;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("""
                insert into member(member_id, provider, provider_user_id, nickname)
                values (?, 'KAKAO', 'rollback-member', 'rollback')
                """, MEMBER_ID);
        jdbcTemplate.update("""
                insert into vehicle_model(
                    manufacturer, model_name, vehicle_type, car_class, is_active)
                values ('롤백제조사', '롤백차량', 'SEDAN', 'Compact', true)
                """);
    }

    @Test
    @DisplayName("사고 저장 실패 시 즉시 생성한 차량도 남지 않는다")
    void rollsBackCreatedVehicleWhenAccidentSaveFails() {
        given(accidentRepository.save(any())).willThrow(new IllegalStateException("save failed"));
        int before = vehicleCount();

        assertThatThrownBy(() -> accidentService.create(
                MEMBER_ID,
                new AccidentCreateRequest(
                        null, new DirectVehicleInput("롤백제조사", "롤백차량", 2020))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("save failed");

        assertThat(vehicleCount()).isEqualTo(before);
    }

    private int vehicleCount() {
        return jdbcTemplate.queryForObject(
                "select count(*) from vehicle where member_id = ?", Integer.class, MEMBER_ID);
    }
}
