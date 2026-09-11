package com.ssafy.a307.estimate.pdf;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * 견적 PDF 의 번호·저장소 키·다운로드 파일명 규칙 (S15P21A307-391·342). <b>모두 서버가 만든다.</b>
 *
 * <pre>
 * report_no  : R-{yyyyMMdd}-{NNNN}                       예) R-20260911-0001
 * 저장소 키  : estimate-reports/{yyyy}/{MM}/{report_no}-{uuid}.pdf
 * 파일명     : 예상견적_{report_no}.pdf
 * </pre>
 *
 * <p><b>일련번호는 날짜마다 0001 부터 다시 시작한다.</b> 날짜는 한국 시간 기준이다 — 사용자와
 * 정비소가 보는 번호라 "오늘 몇 번째" 가 읽혀야 한다. 동시 발급은 날짜별 잠금으로 줄을 세운다
 * ({@link EstimatePdfRepository#lockIssuance}).
 *
 * <p><b>저장소 키 접두어를 {@code estimates/} 로 두지 않았다.</b> 그 접두어는 견적서 원본(차주
 * 이름·차량번호가 실릴 수 있는 문서)의 IAM 경계로 쓰일 자리다. 파일명에는 사용자 입력(차량명 등)을
 * 넣지 않는다 — {@code Content-Disposition} 헤더에 실리는 값이다.
 */
public final class EstimatePdfKeys {

    public static final String PREFIX = "estimate-reports";
    public static final int MAX_DAILY_SEQUENCE = 9999;

    /** 번호의 날짜와 키의 연·월을 가르는 기준 시간대. */
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter YEAR_MONTH =
            DateTimeFormatter.ofPattern("yyyy/MM", Locale.ROOT).withZone(ZONE);

    /**
     * advisory lock 키 공간. 다른 잠금과 겹치지 않게 날짜({@code yyyyMMdd}) 앞에 고정값을 붙인다.
     * 지금 저장소에 다른 advisory lock 은 없다.
     */
    private static final long LOCK_NAMESPACE = 3_307_000_000_000L;

    private EstimatePdfKeys() {
    }

    public static LocalDate issueDay(Instant now) {
        return LocalDate.ofInstant(now, ZONE);
    }

    public static String reportNoPrefix(LocalDate day) {
        return "R-" + day.format(DAY) + "-";
    }

    public static String reportNo(LocalDate day, int sequence) {
        if (sequence < 1 || sequence > MAX_DAILY_SEQUENCE) {
            throw new IllegalStateException("하루 발급 한도를 넘었다: " + sequence);
        }
        return reportNoPrefix(day) + "%04d".formatted(sequence);
    }

    /** 그날 마지막 번호 다음. 그날 첫 발급이면 1 이다. */
    public static int nextSequence(String lastReportNo) {
        if (lastReportNo == null || lastReportNo.isBlank()) {
            return 1;
        }
        return Integer.parseInt(lastReportNo.substring(lastReportNo.lastIndexOf('-') + 1)) + 1;
    }

    public static long issuanceLockKey(LocalDate day) {
        return LOCK_NAMESPACE + Long.parseLong(day.format(DAY));
    }

    /** 번호만으로는 추측 가능하므로 UUID 를 붙여 열거를 어렵게 한다. 받으려면 어차피 서명이 필요하다. */
    public static String storageKey(String reportNo, Instant now) {
        return PREFIX + "/" + YEAR_MONTH.format(now) + "/" + reportNo + "-"
                + UUID.randomUUID().toString().replace("-", "") + ".pdf";
    }

    public static String downloadFilename(String reportNo) {
        return "예상견적_" + reportNo + ".pdf";
    }
}
