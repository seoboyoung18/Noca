package com.ssafy.a307.estimatevalidation.file;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * 견적서 도메인의 저장소 키 규칙. <b>키는 서버만 만든다</b> — 요청으로 받지 않는다.
 *
 * <pre>
 * 원본 문서 : estimates/{yyyy}/{MM}/{uuid}.{ext}
 *            예) estimates/2026/09/6f1c1f8e6a5d4c0b9f2e7a3d5b8c1e04.pdf
 * 검증 PDF  : estimates/reports/{yyyy}/{MM}/{validationId}-{uuid}.pdf
 * </pre>
 *
 * <p><b>두 브랜치가 각자 만든 것을 합친 결과다.</b> 원본 키 규칙은 파이프라인 쪽이,
 * 리포트 키와 다운로드 파일명은 PDF 쪽이 만들었다. 어느 한쪽을 지우지 않고 두 역할을 다 담는다.
 *
 * {@code AccidentImageKeys}·{@code ProfileImageKeys} 와 같은 판단이 들어 있다.
 * <ul>
 *   <li><b>원본 파일명을 키에 넣지 않는다.</b> 사용자 입력이 스토리지 경로에 들어가면 경로 조작과
 *       인코딩 문제가 생긴다. 원본 파일명은 이 도메인에서 아예 저장하지 않는다</li>
 *   <li>연·월로 접두어를 갈라 한 접두어에 오브젝트가 무한정 쌓이지 않게 한다. 로컬 파일시스템에서
 *       특히 중요하고, S3 접두어 분산에도 그대로 쓰인다</li>
 *   <li><b>원본 키 본문은 UUID 뿐이다.</b> 사고·회원 ID 를 넣지 않는 것은 의도다 —
 *       저장소 경로만 보고 누구의 견적서인지 알 수 없어야 한다</li>
 *   <li>PDF 키에만 {@code validationId} 를 넣는 것은 운영 중 오브젝트를 사람이 찾을 수 있게
 *       하기 위해서다. 그것만으로는 추측 가능하므로 UUID 를 함께 붙인다 —
 *       키를 안다고 받을 수 있는 것은 아니지만(presigned 서명이 필요하다) 열거를 어렵게 한다</li>
 * </ul>
 *
 * <p><b>키에 버킷 이름을 넣지 않는다.</b> 버킷을 옮기면 이미 저장된 키가 전부 거짓이 된다.
 * 어느 버킷에 있는지는 어댑터가 안다.
 *
 * <p>키 길이는 {@code estimate_validation.s3_key_file}·{@code estimate_validation_report.s3_key_pdf}
 * 가 모두 {@code VARCHAR(500)} 이라 그 안에 들어가야 한다. 위 형태는 80자를 넘지 않는다.
 */
public final class EstimateDocumentKeys {

    public static final String PREFIX = "estimates";
    public static final String REPORT_SEGMENT = "reports";
    public static final int MAX_KEY_LENGTH = 500;

    /** 접두어를 가르는 기준 시간대. 서비스가 국내용이라 한국 날짜로 나눈다. */
    private static final ZoneId KEY_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YEAR_MONTH =
            DateTimeFormatter.ofPattern("yyyy/MM", Locale.ROOT).withZone(KEY_ZONE);
    private static final DateTimeFormatter FILENAME_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT).withZone(KEY_ZONE);

    private EstimateDocumentKeys() {
    }

    /**
     * 업로드된 견적서 원본.
     *
     * @param extension 점 없는 확장자. {@code EstimateFileValidator} 가 허용 목록으로 이미 걸렀지만
     *                  키 생성 지점에서도 경로 문자를 다시 막는다
     */
    public static String documentKey(String extension, Instant now) {
        String key = PREFIX + "/" + YEAR_MONTH.format(now) + "/"
                + randomId() + "." + requireExtension(extension);
        return requireLength(key);
    }

    /** 검증 결과 PDF. */
    public static String reportKey(long validationId, Instant now) {
        if (validationId < 1) throw new IllegalArgumentException("validationId must be positive");
        String key = PREFIX + "/" + REPORT_SEGMENT + "/" + YEAR_MONTH.format(now) + "/"
                + validationId + "-" + randomId() + ".pdf";
        return requireLength(key);
    }

    /**
     * 다운로드 시 브라우저가 저장할 파일명.
     *
     * <p><b>사용자 입력을 넣지 않는다.</b> 정비소명이나 원본 파일명을 넣으면 헤더 인젝션과
     * 인코딩 문제가 생긴다 — {@code Content-Disposition} 헤더에 실리는 값이다.
     * 검증 번호와 날짜만으로도 여러 건을 구분할 수 있다.
     */
    public static String downloadFilename(long validationId, Instant generatedAt) {
        return "견적검증_%d_%s.pdf".formatted(validationId, FILENAME_DATE.format(generatedAt));
    }

    private static String randomId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String requireExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            throw new IllegalArgumentException("extension is required");
        }
        String stripped = extension.strip().toLowerCase(Locale.ROOT);
        if (stripped.contains("/") || stripped.contains("\\") || stripped.contains(".")) {
            throw new IllegalArgumentException("extension must not contain path separators or dots");
        }
        return stripped;
    }

    private static String requireLength(String key) {
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("storage key must not exceed " + MAX_KEY_LENGTH + " characters");
        }
        return key;
    }
}
