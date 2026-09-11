package com.ssafy.a307.common.exception;

import com.ssafy.a307.common.response.ErrorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB 제약 위반과 필수 파라미터 누락이 500 으로 새지 않는지.
 *
 * <p><b>왜 통합 테스트가 아닌가</b> — 이 두 경로는 H2 에서 <b>결정적으로 재현되지 않는다.</b>
 * {@code AdminPartNameMappingIntegrityTest} 의 동시 등록 8건은 H2 가 사실상 직렬화해
 * 사전 {@code existsById} 검사에서 7건이 걸렸고(실측 분포:
 * {@code DUPLICATE_PART_NAME_MAPPING} ×7 + {@code OK} ×1), INSERT 단계까지 내려간 경쟁은
 * 일어나지 않았다. 즉 <b>서비스의 catch 는 통합 테스트로 증명되지 않았다.</b>
 * 그래서 번역 규칙 자체를 여기서 직접 잠근다.
 *
 * <p>이 클래스는 {@code @SpringBootTest} 가 아니라 핸들러를 그대로 만들어 부른다 —
 * 검증 대상이 스프링 배선이 아니라 <b>상태 코드와 메시지</b>이기 때문이다.
 */
@DisplayName("공통 예외 번역")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("UNIQUE 제약 위반은 409 이고 SQL·제약명을 밖으로 흘리지 않는다")
    void dataIntegrityBecomesConflictWithoutLeakingSchemaDetails() {
        String rawDbMessage = """
                could not execute statement [Unique index or primary key violation: \
                "PRIMARY KEY ON PUBLIC.PART_NAME_MAPPING(RAW_NAME)"] \
                [insert into part_name_mapping (part_code,raw_name) values (?,?)]""";

        ResponseEntity<ErrorResponse> response =
                handler.handleDataIntegrity(new DataIntegrityViolationException(rawDbMessage));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo(ErrorCode.CONFLICT.name());
        assertThat(response.getBody().error().message())
                .as("제약명·테이블명·SQL 원문이 응답에 섞이면 FE 가 그것을 문자열 비교하기 시작한다")
                .doesNotContain("PRIMARY KEY", "PART_NAME_MAPPING", "insert into", "Unique index")
                .contains("이미 존재하는 값");
    }

    @Test
    @DisplayName("필수 쿼리 파라미터 누락은 400 이고 파라미터 이름을 알려 준다")
    void missingParameterBecomesBadRequest() {
        ResponseEntity<ErrorResponse> response = handler.handleMissingParameter(
                new MissingServletRequestParameterException("rawName", "String"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo(ErrorCode.INVALID_REQUEST.name());
        assertThat(response.getBody().error().message())
                .as("무엇을 빠뜨렸는지 모르면 FE 가 고칠 수 없다")
                .contains("rawName");
    }
}
