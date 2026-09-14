package com.ssafy.a307.estimate;

import com.ssafy.a307.estimate.dto.EstimateNotice;
import com.ssafy.a307.estimate.service.EstimateNoticeProvider;
import com.ssafy.a307.estimatevalidation.service.EstimateValidationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 고지 문구 공급 (S15P21A307-288 · S15P21A307-289).
 *
 * <p><b>이 테스트가 지키는 것은 "문구가 갈라지지 않는다" 다.</b> 같은 문장이 지금 세 곳에
 * 적혀 있다 — {@code EstimateValidationService.LEGAL_NOTICE} 상수, 운영 DB 에 넣을
 * 마이그레이션 SQL, 테스트용 {@code schema-h2.sql} 시드. 셋 중 하나만 고치면 화면과 리포트가
 * 다른 문장을 보여 주는데, 배포하고 눈으로 보기 전까지 아무도 모른다.
 *
 * <p><b>왜 상수를 지우지 않았나</b> — {@code EstimateValidationService} 는 견적서 검증 도메인
 * (김재원 담당)이고 그 화면·PDF 는 당분간 서비스에 쓰이지 않는다. 쓰이지 않는 경로를 지금
 * 고쳐 머지 충돌을 만들 이유가 없다. 대신 상수와 테이블이 어긋나면 <b>빌드가 깨지도록</b>
 * 여기서 묶어 둔다. 검증 쪽이 실제로 쓰이게 되면 그때 {@link EstimateNoticeProvider} 주입으로
 * 바꾸고 이 테스트의 상수 비교를 지우면 된다.
 */
@DisplayName("견적 한계 고지 문구 (S15P21A307-287)")
class EstimateNoticeProviderTest {

    @Nested
    @DisplayName("문구 정합성 — 상수 · 마이그레이션 · 테스트 시드가 같은 문장이다")
    class SeedConformity {

        @Test
        @DisplayName("마이그레이션 SQL 이 상수와 같은 문장을 넣는다")
        void migrationMatchesConstant() {
            String migration = read(repoFile("Docs/Erd/migrations/2026-09-14-estimate-notice.sql"));

            assertThat(migration)
                    .as("마이그레이션이 넣는 문장이 상수와 다르다. 이관이지 개정이 아니므로 값이 같아야 한다")
                    .contains(EstimateValidationService.LEGAL_NOTICE);
        }

        @Test
        @DisplayName("H2 테스트 시드가 상수와 같은 문장을 넣는다")
        void h2SeedMatchesConstant() {
            assertThat(read(h2Schema()))
                    .as("테스트 시드가 상수와 다르면 테스트는 통과하는데 운영만 다른 문장이 나간다")
                    .contains(EstimateValidationService.LEGAL_NOTICE);
        }

        @Test
        @DisplayName("상수를 코드에서 조립하지 않는다 — 파일 대조가 의미를 가지려면 리터럴이어야 한다")
        void constantIsPlainLiteral() {
            assertThat(EstimateValidationService.LEGAL_NOTICE)
                    .isNotBlank()
                    .doesNotContain("%s", "{}");
        }

        private Path h2Schema() {
            Path fromModule = Path.of("src/test/resources/schema-h2.sql");
            return Files.exists(fromModule) ? fromModule : Path.of("backend").resolve(fromModule);
        }

        private Path repoFile(String relativePath) {
            Path fromModule = Path.of("..").resolve(relativePath);
            return Files.exists(fromModule) ? fromModule : Path.of(relativePath);
        }

        private String read(Path path) {
            try {
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                throw new UncheckedIOException("파일을 읽지 못했다: " + path.toAbsolutePath(), e);
            }
        }
    }

    @Nested
    @SpringBootTest
    @Transactional
    @DisplayName("테이블에서 읽는다")
    class FromTable {

        @Autowired
        private EstimateNoticeProvider noticeProvider;

        @Autowired
        private JdbcTemplate jdbcTemplate;

        @Test
        @DisplayName("활성 문구를 code · message 로 내려보낸다")
        void activeNoticesAreReturned() {
            List<EstimateNotice> notices = noticeProvider.activeNotices();

            assertThat(notices)
                    .extracting(EstimateNotice::code)
                    .contains(EstimateNoticeProvider.LEGAL_NOTICE_CODE);
            assertThat(notices)
                    .filteredOn(notice -> notice.code().equals(EstimateNoticeProvider.LEGAL_NOTICE_CODE))
                    .singleElement()
                    .extracting(EstimateNotice::message)
                    .isEqualTo(EstimateValidationService.LEGAL_NOTICE);
        }

        @Test
        @DisplayName("리포트용 단일 문장도 같은 값이다")
        void legalNoticeMatchesSeed() {
            assertThat(noticeProvider.legalNotice())
                    .isEqualTo(EstimateValidationService.LEGAL_NOTICE);
        }

        @Test
        @DisplayName("display_order 순으로 나온다 — 같은 견적을 두 번 열어도 순서가 같아야 한다")
        void noticesAreOrdered() {
            insert("ZZZ_FIRST", "먼저 나올 문구", 0, true);
            insert("AAA_LAST", "나중에 나올 문구", 9, true);

            assertThat(noticeProvider.activeNotices())
                    .extracting(EstimateNotice::code)
                    .containsSubsequence("ZZZ_FIRST", EstimateNoticeProvider.LEGAL_NOTICE_CODE, "AAA_LAST");
        }

        @Test
        @DisplayName("내려 둔 문구는 목록에도 리포트에도 나오지 않는다")
        void inactiveNoticeIsHidden() {
            insert("RETIRED", "더 이상 쓰지 않는 문구", 5, false);

            assertThat(noticeProvider.activeNotices())
                    .extracting(EstimateNotice::code)
                    .doesNotContain("RETIRED");
        }

        @Test
        @DisplayName("LEGAL_NOTICE 를 내리면 리포트용 문장이 null 이 된다 — requireSections 가 막을 몫이다")
        void legalNoticeIsNullWhenRetired() {
            jdbcTemplate.update("UPDATE estimate_notice SET is_active = FALSE WHERE code = ?",
                    EstimateNoticeProvider.LEGAL_NOTICE_CODE);

            assertThat(noticeProvider.legalNotice()).isNull();
        }

        /**
         * 문구는 운영자가 psql 로 넣는 값이라 애플리케이션에 쓰기 경로가 없다.
         * 테스트에서만 JDBC 로 직접 넣는다 — 그러려고 리포지토리에 저장 메서드를 열지 않는다.
         */
        private void insert(String code, String message, int order, boolean active) {
            jdbcTemplate.update("""
                    INSERT INTO estimate_notice (code, message, display_order, is_active)
                    VALUES (?, ?, ?, ?)
                    """, code, message, order, active);
        }
    }
}
