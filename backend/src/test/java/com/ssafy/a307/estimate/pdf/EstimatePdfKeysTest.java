package com.ssafy.a307.estimate.pdf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("견적 PDF 번호·키 규칙")
class EstimatePdfKeysTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 11);

    @Test
    @DisplayName("번호는 R-날짜-4자리 일련번호다")
    void reportNoFormat() {
        assertThat(EstimatePdfKeys.reportNo(DAY, 1)).isEqualTo("R-20260911-0001");
        assertThat(EstimatePdfKeys.reportNo(DAY, 42)).isEqualTo("R-20260911-0042");
    }

    @Test
    @DisplayName("그날 첫 발급은 1, 이후는 마지막 번호 다음이다")
    void nextSequence() {
        assertThat(EstimatePdfKeys.nextSequence(null)).isEqualTo(1);
        assertThat(EstimatePdfKeys.nextSequence("R-20260911-0041")).isEqualTo(42);
    }

    @Test
    @DisplayName("하루 한도를 넘으면 발급하지 않는다")
    void dailyLimit() {
        assertThatThrownBy(() -> EstimatePdfKeys.reportNo(DAY, EstimatePdfKeys.MAX_DAILY_SEQUENCE + 1))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 자정 직전 UTC 는 한국 날짜로 이미 다음 날이다. 번호의 날짜는 사용자가 보는 날짜여야 한다. */
    @Test
    @DisplayName("발급 날짜는 한국 시간 기준이다")
    void issueDayIsKst() {
        assertThat(EstimatePdfKeys.issueDay(Instant.parse("2026-09-10T15:30:00Z"))).isEqualTo(DAY);
    }

    @Test
    @DisplayName("저장소 키는 전용 접두어·연월·번호를 담고, 파일명에는 사용자 입력이 없다")
    void storageKeyAndFilename() {
        String key = EstimatePdfKeys.storageKey("R-20260911-0001", Instant.parse("2026-09-11T03:00:00Z"));

        assertThat(key).startsWith("estimate-reports/2026/09/R-20260911-0001-").endsWith(".pdf");
        assertThat(EstimatePdfKeys.downloadFilename("R-20260911-0001")).isEqualTo("예상견적_R-20260911-0001.pdf");
    }

    @Test
    @DisplayName("잠금 키는 날짜마다 다르다")
    void lockKeyPerDay() {
        assertThat(EstimatePdfKeys.issuanceLockKey(DAY))
                .isNotEqualTo(EstimatePdfKeys.issuanceLockKey(DAY.plusDays(1)));
    }
}
