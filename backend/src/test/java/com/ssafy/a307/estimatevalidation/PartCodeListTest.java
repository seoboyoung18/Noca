package com.ssafy.a307.estimatevalidation;

import com.ssafy.a307.estimatevalidation.entity.PartCode;
import com.ssafy.a307.estimatevalidation.entity.PartCodeScope;
import com.ssafy.a307.estimatevalidation.repository.PartCodeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사용자가 고를 수 있는 부위 목록 (S15P21A307-568).
 *
 * <p>확장 코드는 유사 사례 검색 코퍼스에 없어 골라도 분석이 이어지지 않는다. 비활성 부위도
 * 고르게 해선 안 된다. 그 둘을 빼고 표시 순서대로 나오는지 본다.
 */
@SpringBootTest
@Transactional
@DisplayName("부위 목록")
class PartCodeListTest {

    @Autowired private PartCodeRepository partCodeRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("활성 AI 라벨 부위만 표시 순서대로 나온다 — 확장 코드·비활성은 빠진다")
    void onlyActiveAiLabelsInDisplayOrder() {
        insert("ZZ568_SECOND", 2, true, "AI_LABEL");
        insert("ZZ568_FIRST", 1, true, "AI_LABEL");
        insert("ZZ568_OFF", 0, false, "AI_LABEL");
        insert("ZZ568_EXT", 0, true, "EXTENDED");

        assertThat(partCodeRepository
                .findByActiveTrueAndCodeScopeOrderByDisplayOrderAscPartCodeAsc(PartCodeScope.AI_LABEL)
                .stream()
                .map(PartCode::getPartCode)
                .filter(code -> code.startsWith("ZZ568_"))
                .toList())
                .containsExactly("ZZ568_FIRST", "ZZ568_SECOND");
    }

    private void insert(String partCode, int displayOrder, boolean active, String scope) {
        jdbcTemplate.update("""
                insert into part_code (part_code, name_ko, layout_zone, display_order, is_active, code_scope)
                values (?, '시험 부위', 'FRONT', ?, ?, ?)
                """, partCode, displayOrder, active, scope);
    }
}
