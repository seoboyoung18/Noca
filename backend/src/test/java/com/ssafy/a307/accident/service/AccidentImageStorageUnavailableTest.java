package com.ssafy.a307.accident.service;

import com.ssafy.a307.accident.dto.ImageUploadCompleteItem;
import com.ssafy.a307.accident.dto.ImageUploadCompleteRequest;
import com.ssafy.a307.accident.dto.ImageUploadUrlItem;
import com.ssafy.a307.accident.dto.ImageUploadUrlRequest;
import com.ssafy.a307.accident.image.AccidentImageStoragePort;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * <b>S3 어댑터가 없는 지금의 실제 상태</b>를 고정한다.
 * <p>
 * 이 테스트가 존재하는 이유는 두 가지다. 첫째, 어댑터가 없다는 사실이 <b>가짜 성공</b>이 아니라
 * 503 으로 정직하게 드러나는지 확인한다. 둘째, 나중에 어댑터가 붙으면 이 테스트가 실패하므로
 * "포트만 있는 상태" 를 벗어난 시점이 눈에 보인다 — 그때 이 클래스를 지우고
 * 실제 저장소 통합 테스트로 바꾸면 된다.
 */
@SpringBootTest
@DisplayName("이미지 저장소 어댑터 부재")
class AccidentImageStorageUnavailableTest {

    private static final long MEMBER_ID = 92_001L;
    private static final long MODEL_ID = 92_002L;
    private static final long VEHICLE_ID = 92_003L;
    private static final long ACCIDENT_ID = 92_004L;

    @Autowired
    private AccidentImageService service;
    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("""
                insert into member(member_id, provider, provider_user_id, nickname)
                values (?, 'KAKAO', 'no-storage-owner', '저장소없음')
                """, MEMBER_ID);
        jdbcTemplate.update("""
                insert into vehicle_model(model_id, manufacturer, model_name, vehicle_type, car_class, is_active)
                values (?, '무저장소제조사', '무저장소차량', 'SEDAN', 'Compact', true)
                """, MODEL_ID);
        jdbcTemplate.update("""
                insert into vehicle(vehicle_id, member_id, model_id, model_year) values (?, ?, ?, 2020)
                """, VEHICLE_ID, MEMBER_ID, MODEL_ID);
        jdbcTemplate.update("""
                insert into accident(
                    accident_id, vehicle_id, vehicle_input_type, snapshot_model_id,
                    snapshot_manufacturer, snapshot_model_name, snapshot_vehicle_type,
                    snapshot_car_class, snapshot_model_year)
                values (?, ?, 'REGISTERED', ?, '무저장소제조사', '무저장소차량', 'SEDAN', 'Compact', 2020)
                """, ACCIDENT_ID, VEHICLE_ID, MODEL_ID);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    @DisplayName("저장소 포트 구현체가 등록되어 있지 않다")
    void noAdapterIsRegistered() {
        assertThat(applicationContext.getBeansOfType(AccidentImageStoragePort.class)).isEmpty();
    }

    @Test
    @DisplayName("발급 요청은 503 이고 accident_image 행이 생기지 않는다")
    void issueFails() {
        BusinessException e = catchThrowableOfType(BusinessException.class,
                () -> service.issueUploadUrls(MEMBER_ID, ACCIDENT_ID, new ImageUploadUrlRequest(
                        List.of(new ImageUploadUrlItem("front.jpg", "image/jpeg", 1024L, null)))));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(rows()).isZero();
    }

    @Test
    @DisplayName("완료 통보도 503 이다 — 개별 이미지 실패로 뭉개지 않는다")
    void completeFails() {
        jdbcTemplate.update("""
                insert into accident_image(accident_id, original_filename) values (?, 'front.jpg')
                """, ACCIDENT_ID);
        Long imageId = jdbcTemplate.queryForObject("""
                select max(image_id) from accident_image where accident_id = ?
                """, Long.class, ACCIDENT_ID);

        BusinessException e = catchThrowableOfType(BusinessException.class,
                () -> service.complete(MEMBER_ID, ACCIDENT_ID, new ImageUploadCompleteRequest(
                        List.of(new ImageUploadCompleteItem(imageId)))));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("상태 조회는 저장소 없이도 동작한다 — DB 만 읽는다")
    void listWorksWithoutStorage() {
        assertThat(service.list(MEMBER_ID, ACCIDENT_ID).total()).isZero();
    }

    private int rows() {
        return jdbcTemplate.queryForObject("""
                select count(*) from accident_image where accident_id = ?
                """, Integer.class, ACCIDENT_ID);
    }

    private void cleanUp() {
        jdbcTemplate.update("""
                delete from accident_image_asset where image_id in (
                    select image_id from accident_image where accident_id = ?)
                """, ACCIDENT_ID);
        jdbcTemplate.update("delete from accident_image where accident_id = ?", ACCIDENT_ID);
        jdbcTemplate.update("delete from accident where accident_id = ?", ACCIDENT_ID);
        jdbcTemplate.update("delete from vehicle where vehicle_id = ?", VEHICLE_ID);
        jdbcTemplate.update("delete from vehicle_model where model_id = ?", MODEL_ID);
        jdbcTemplate.update("delete from member where member_id = ?", MEMBER_ID);
    }
}
